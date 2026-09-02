package com.ishaan.essentialvoice.buds

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ishaan.essentialvoice.Prefs

/**
 * One-tap connect for a pair of earbuds, from the home screen or the Quick
 * Settings panel.
 *
 * It is here because of the microphone, not the speakers. Dictating through
 * earbuds means HFP has to be up, and the four taps through Settings →
 * Connected devices → the buds → wait are four taps in front of a feature whose
 * whole point is that it is faster than typing.
 *
 * **There is no public API for this and there is unlikely ever to be one.**
 * `BluetoothA2dp` in the public SDK exposes only `getConnectedDevices`,
 * `getDevicesMatchingConnectionStates`, `getConnectionState`, `isA2dpPlaying`
 * and `getSupportedCodecTypes`; the documented `connect()` is `@SystemApi`
 * behind `BLUETOOTH_PRIVILEGED`, which is `signature|privileged` and can never
 * be held by an app anybody installs. Reached reflectively it nevertheless goes
 * straight through — measured on Nothing OS 4.1 / Android 16, from a package
 * targeting SDK 35 holding nothing but `BLUETOOTH_CONNECT`:
 *
 *     connect() on BluetoothA2dp -> true
 *     btif_av: BTA_AV_OPEN_EVT(0x2) status=0(SUCCESS)
 *     HeadsetStateMachine state=Connected
 *
 * The call reaches `A2dpService` in the Bluetooth server process, the native
 * stack runs the whole connection, and HFP follows on its own — which is the
 * part that matters here, because it is what brings up the microphone.
 *
 * None of that is promised by any API contract, so **every caller must handle
 * the refusal**, and on some ROM somewhere it will be refused. [invoke] returns
 * false rather than throwing, and the surfaces fall back to opening Bluetooth
 * settings, where the buds are still one tap away.
 */
object Buds {

    const val TAG = "EVBuds"

    // ---- what the surfaces paint from --------------------------------------

    /**
     * The cached connection state, so a widget can paint itself synchronously.
     *
     * Written by [BudsStateReceiver] when the system reports an ACL change. It
     * is a cache and it *does* drift — an ACL broadcast is missed whenever the
     * phone reboots, or the buds walk out of range while the package is in the
     * stopped state. It is therefore only ever used for painting. Anything that
     * acts asks the stack instead: see [connectedNow].
     */
    fun isConnectedCached(ctx: Context): Boolean =
        Prefs.get(ctx).budsConnected

    fun setConnectedCached(ctx: Context, connected: Boolean) {
        Prefs.get(ctx).budsConnected = connected
    }

    /**
     * Whether the platform refused the direct route last time.
     *
     * When it did, the surfaces stop offering a connect they cannot perform and
     * offer Bluetooth settings instead. A widget cannot simply open settings at
     * the moment it finds out: an `AppWidgetProvider` is a BroadcastReceiver,
     * and a receiver's `startActivity` is refused outright —
     * "Background activity launch blocked!" — while the toast that would have
     * explained it is suppressed whenever the app's notifications are off. So
     * the failure is remembered, the widget repaints to say so, and the *next*
     * tap carries an activity PendingIntent, which the launcher sends and which
     * is therefore allowed.
     */
    fun needsSettings(ctx: Context): Boolean = Prefs.get(ctx).budsBlocked

    fun setNeedsSettings(ctx: Context, blocked: Boolean) {
        Prefs.get(ctx).budsBlocked = blocked
    }

    // ---- the adapter -------------------------------------------------------

    fun adapter(ctx: Context): BluetoothAdapter? =
        ctx.getSystemService(BluetoothManager::class.java)?.adapter

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun isBluetoothOn(ctx: Context): Boolean = adapter(ctx)?.isEnabled == true

    /**
     * The chosen device, if one has been chosen and is still bonded.
     *
     * The bonded check is not decoration. `getRemoteDevice` answers for any
     * well-formed address whether or not the phone has ever seen it, so
     * unpairing the buds in Bluetooth settings left this returning a device
     * that every connect would refuse — a widget still captioned with their
     * name, and a tap that could only fail. Unpaired is the same as unchosen.
     */
    // Every Bluetooth call below is behind hasPermission(ctx) and inside a
    // runCatching. Lint cannot follow the check through the helper, so it
    // reports each one; the annotation says "checked", not "ignored".
    @SuppressLint("MissingPermission")
    fun device(ctx: Context): BluetoothDevice? {
        val address = Prefs.get(ctx).now.budsAddress.takeIf { it.isNotEmpty() } ?: return null
        if (!hasPermission(ctx)) return null
        val a = adapter(ctx) ?: return null
        val device = runCatching { a.getRemoteDevice(address) }.getOrNull() ?: return null
        // Only when the adapter can actually answer. With Bluetooth off the
        // bonded set reads empty, and treating that as "unpaired" would throw
        // the choice away every time the radio was switched off.
        if (!isBluetoothOn(ctx)) return device
        val bonded = runCatching { a.bondedDevices.orEmpty() }.getOrNull() ?: return device
        return device.takeIf { d -> bonded.any { it.address.equals(d.address, true) } }
    }

    /**
     * Every bonded audio device, for the picker.
     *
     * Filtered to headsets and speakers rather than listing everything paired:
     * a picker offering a car, a watch and a keyboard as things to "connect for
     * dictation" is a picker that has not been thought about. There is no
     * hardcoded address anywhere in this app — whoever installs it picks their
     * own.
     */
    // Every Bluetooth call below is behind hasPermission(ctx) and inside a
    // runCatching. Lint cannot follow the check through the helper, so it
    // reports each one; the annotation says "checked", not "ignored".
    @SuppressLint("MissingPermission")
    fun bondedAudioDevices(ctx: Context): List<BluetoothDevice> {
        if (!hasPermission(ctx)) return emptyList()
        val a = adapter(ctx) ?: return emptyList()
        return runCatching {
            a.bondedDevices.orEmpty().filter { d ->
                when (d.bluetoothClass?.majorDeviceClass) {
                    BluetoothClass.Device.Major.AUDIO_VIDEO -> true
                    else -> false
                }
            }.sortedBy { runCatching { it.alias ?: it.name }.getOrNull().orEmpty().lowercase() }
        }.getOrDefault(emptyList())
    }

    // alias/name need BLUETOOTH_CONNECT. Every caller reaches a device through
    // device() or bondedAudioDevices(), both of which return nothing without the
    // permission — and the runCatching is the belt to that braces.
    @SuppressLint("MissingPermission")
    fun label(device: BluetoothDevice): String =
        runCatching { device.alias ?: device.name }.getOrNull().orEmpty()
            .ifEmpty { device.address }

    // ---- asking the stack --------------------------------------------------

    /** Whether [device] is in the profile proxy's connected list. */
    fun proxySaysConnected(proxy: BluetoothProfile?, ctx: Context, address: String): Boolean {
        if (proxy == null || !hasPermission(ctx)) return false
        return runCatching {
            proxy.connectedDevices.any { it.address.equals(address, ignoreCase = true) }
        }.getOrDefault(false)
    }

    /**
     * Ask the stack whether the chosen buds are connected, and correct the cache.
     *
     * The surfaces have to *paint* from the cache — a RemoteViews tree is built
     * synchronously and a tile is painted the moment it is bound — but the cache
     * drifts, because it is only written when an ACL broadcast arrives and one is
     * missed whenever the phone reboots or the package is in the stopped state
     * after an install. A tile reading "Tap to connect" over a pair that is
     * already connected is worse than a slow tile: the tap then does the exact
     * opposite of what the tile offered. So both surfaces paint from the cache
     * first for speed, then call this and repaint when it answers.
     */
    fun queryConnected(ctx: Context, done: (Boolean) -> Unit) {
        val address = Prefs.get(ctx).now.budsAddress
        if (address.isEmpty() || !hasPermission(ctx) || !isBluetoothOn(ctx)) {
            setConnectedCached(ctx, false)
            done(false)
            return
        }
        withProfile(ctx, BluetoothProfile.A2DP) { proxy ->
            if (proxy == null) {
                // Nothing learned; leave the cache as it was rather than
                // asserting a disconnect that was never observed.
                done(isConnectedCached(ctx))
                return@withProfile
            }
            val connected = proxySaysConnected(proxy, ctx, address)
            val changed = connected != isConnectedCached(ctx)
            setConnectedCached(ctx, connected)
            // Whoever asked, everyone gets told. The two surfaces each hold their
            // own idea of the state and are repainted by different things — the
            // tile every time Quick Settings opens, the widget only when
            // something pokes it — so without this the tile can correct itself
            // off a missed ACL broadcast while the widget goes on showing the
            // old answer, and the two visibly disagree on the same screen.
            if (changed) {
                BudsWidget.refresh(ctx)
                BudsTile.refresh(ctx)
            }
            done(connected)
        }
    }

    /**
     * What to call the chosen buds, read live from the adapter.
     *
     * Renaming a pair in Bluetooth settings changes the alias, and the copy
     * stored when they were picked goes stale. Nothing *functional* depends on
     * either — every connect goes through the address, which a rename does not
     * touch — but a widget captioned with an old name looks broken. Falls back to
     * the stored copy for the case where the adapter is off and cannot be asked.
     */
    // Every Bluetooth call below is behind hasPermission(ctx) and inside a
    // runCatching. Lint cannot follow the check through the helper, so it
    // reports each one; the annotation says "checked", not "ignored".
    @SuppressLint("MissingPermission")
    fun liveLabel(ctx: Context): String {
        val stored = Prefs.get(ctx).now.budsName
        val device = device(ctx) ?: return stored
        return runCatching { device.alias ?: device.name }.getOrNull()
            .orEmpty().ifEmpty { stored }
    }

    // ---- connect / disconnect ----------------------------------------------

    /**
     * The hidden `connect()` / `disconnect()` on a profile proxy.
     *
     * Returns false when the platform refuses, so the caller can fall back
     * rather than silently doing nothing. It never throws: on a ROM that blocks
     * the reflection this is a `NoSuchMethodException`, and on one that enforces
     * `BLUETOOTH_PRIVILEGED` it is an `InvocationTargetException` wrapping a
     * `SecurityException` — neither is exceptional here, both just mean "use
     * the settings route".
     */
    fun invoke(proxy: BluetoothProfile?, device: BluetoothDevice?, connect: Boolean): Boolean {
        if (proxy == null || device == null) return false
        val name = if (connect) "connect" else "disconnect"
        return try {
            val m = proxy.javaClass.getMethod(name, BluetoothDevice::class.java)
            m.isAccessible = true
            val result = m.invoke(proxy, device)
            val ok = result !is Boolean || result
            Log.i(TAG, "$name() on ${proxy.javaClass.simpleName} -> $result")
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "$name() unavailable on this build", t)
            false
        }
    }

    /** How long to wait for a profile proxy before giving up on it. */
    private const val PROXY_WAIT_MS = 3_000L

    /**
     * Asks the system for a profile proxy and releases it again immediately.
     *
     * [ready] is called exactly once, always. That is the whole contract, and
     * it was not being kept: `getProfileProxy` returning true only means the
     * request was accepted, and when `onServiceConnected` never arrived — the
     * radio going off mid-flight is enough — the callback was simply dropped.
     * Everything here hangs off that callback, so a dropped one is a tap that
     * does nothing and says nothing: no connect, no fallback to settings, no
     * outcome to paint. A timeout is not tidiness, it is the difference between
     * a slow tap and a dead one.
     */
    fun withProfile(ctx: Context, profile: Int, ready: (BluetoothProfile?) -> Unit) {
        val a = adapter(ctx)
        if (a == null) { ready(null); return }

        var answered = false
        val give = { proxy: BluetoothProfile? ->
            if (!answered) {
                answered = true
                ready(proxy)
            }
        }
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                try {
                    give(proxy)
                } finally {
                    // Holding a proxy is what keeps an app process warm.
                    runCatching { a.closeProfileProxy(p, proxy) }
                }
            }
            /** The binding dropped: an answer, and the only one coming. */
            override fun onServiceDisconnected(p: Int) { give(null) }
        }
        if (!runCatching { a.getProfileProxy(ctx.applicationContext, listener, profile) }
                .getOrDefault(false)
        ) {
            give(null)
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (!answered) Log.w(TAG, "no profile proxy for $profile after ${PROXY_WAIT_MS}ms")
            give(null)
        }, PROXY_WAIT_MS)
    }
}
