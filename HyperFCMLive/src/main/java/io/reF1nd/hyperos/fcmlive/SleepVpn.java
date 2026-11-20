package io.reF1nd.hyperos.fcmlive;

import android.content.Context;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;

/** Resolve the app tunnel used by one GMS UID at the time the night firewall starts. */
final class SleepVpn {
    static int ownerForUid(int uid) throws ReflectiveOperationException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return -1;
        var binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, Context.CONNECTIVITY_SERVICE);
        if (binder == null) throw new IllegalStateException("Connectivity service unavailable");
        var service = binder.queryLocalInterface("android.net.IConnectivityManager");
        if (service == null) throw new IllegalStateException("Connectivity service is not local");

        // Ask for this UID's default network even when its traffic is temporarily blocked.
        var network = (Network) service.getClass().getMethod("getActiveNetworkForUid", int.class, boolean.class)
                .invoke(service, uid, true);
        if (network == null) return -1;
        // The public capabilities query strips ownerUid for every caller except the owner.
        var getCapabilities = service.getClass().getDeclaredMethod("getNetworkCapabilitiesInternal", Network.class);
        getCapabilities.setAccessible(true);
        var caps = (NetworkCapabilities) getCapabilities.invoke(service, network);
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return -1;
        int ownerUid = caps.getOwnerUid();
        if (!Process.isApplicationUid(ownerUid)) return -1;
        var info = caps.getTransportInfo();
        if (info == null || !"android.net.VpnTransportInfo".equals(info.getClass().getName())) return -1;
        int type = (int) info.getClass().getMethod("getType").invoke(info);
        // ConnectivityService.isVpnServiceVpn on HyperOS 4 accepts these VpnService types.
        return type == 1 || type == 5 ? ownerUid : -1;
    }

    private SleepVpn() {}
}
