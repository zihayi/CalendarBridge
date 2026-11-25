package local.calendar.readonly;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

final class WifiDiscovery {
    static final int PORT=8767;
    static final class Computer {
        final String name,server,fingerprint;
        final boolean pairing;
        Computer(String name,String server,String fingerprint,boolean pairing){this.name=name;this.server=server;this.fingerprint=fingerprint;this.pairing=pairing;}
    }
    static Network wifi(Context context)throws IOException {
        ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        for(Network network:manager.getAllNetworks()){
            NetworkCapabilities capabilities=manager.getNetworkCapabilities(network);
            if(capabilities!=null&&capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return network;
        }
        throw new IOException("请先连接与电脑相同的 Wi-Fi。");
    }
    static URLConnection connection(Context context,URL url)throws IOException{return wifi(context).openConnection(url);}
    static String randomHex(int size){return PairingProof.randomHex(size);}
    static String digest(String value)throws Exception{return PairingProof.digest(value);}
    static String verificationCode(String id,String fingerprint,String nonce,String secret)throws Exception{return PairingProof.verificationCode(id,fingerprint,nonce,secret);}
    static List<Computer> find(Context context)throws Exception{
        Network network=wifi(context);
        ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        LinkProperties properties=manager.getLinkProperties(network);
        Set<InetAddress> addresses=new LinkedHashSet<>();
        addresses.add(InetAddress.getByName("255.255.255.255"));
        if(properties!=null)for(LinkAddress link:properties.getLinkAddresses()){
            if(!(link.getAddress() instanceof Inet4Address)||link.getPrefixLength()<1||link.getPrefixLength()>30)continue;
            byte[] bytes=link.getAddress().getAddress();int value=0;for(byte item:bytes)value=(value<<8)|(item&255);
            int broadcast=value|(-1>>>link.getPrefixLength());
            addresses.add(InetAddress.getByAddress(new byte[]{(byte)(broadcast>>>24),(byte)(broadcast>>>16),(byte)(broadcast>>>8),(byte)broadcast}));
        }
        String nonce=randomHex(16);
        byte[] request=new JSONObject().put("type","calendarbridge-discover").put("version",1).put("nonce",nonce).toString().getBytes(StandardCharsets.UTF_8);
        LinkedHashMap<String,Computer> found=new LinkedHashMap<>();
        try(DatagramSocket socket=new DatagramSocket()){
            network.bindSocket(socket);socket.setBroadcast(true);socket.setSoTimeout(300);
            long end=SystemClock.elapsedRealtime()+3000,lastSend=0;
            while(SystemClock.elapsedRealtime()<end){
                if(SystemClock.elapsedRealtime()-lastSend>800){for(InetAddress address:addresses){try{socket.send(new DatagramPacket(request,request.length,address,PORT));}catch(IOException ignored){}}lastSend=SystemClock.elapsedRealtime();}
                try{
                    byte[] bytes=new byte[2048];DatagramPacket packet=new DatagramPacket(bytes,bytes.length);socket.receive(packet);
                    String host=packet.getAddress().getHostAddress();if(!PairingConfig.privateAddress(host))continue;
                    JSONObject data=new JSONObject(new String(packet.getData(),0,packet.getLength(),StandardCharsets.UTF_8));
                    String fingerprint=data.optString("certificateSha256");int port=data.optInt("port");
                    if(!data.optString("type").equals("calendarbridge-receiver")||data.optInt("version")!=1||!data.optString("nonce").equals(nonce)||!fingerprint.matches("[0-9a-f]{64}")||port<1||port>65535)continue;
                    String name=data.optString("name","WallNote");if(name.length()>100)name=name.substring(0,100);
                    found.put(fingerprint,new Computer(name,"https://"+host+":"+port,fingerprint,data.optBoolean("pairing")));
                    if(found.size()>=20)break;
                }catch(SocketTimeoutException ignored){}catch(org.json.JSONException ignored){}
            }
        }
        return new ArrayList<>(found.values());
    }
    static PairingConfig reconnect(Context context,PairingConfig saved)throws Exception{
        for(Computer computer:find(context))if(computer.fingerprint.equals(saved.fingerprint)){
            PairingConfig next=new PairingConfig(computer.server,saved.token,saved.fingerprint);
            CalendarSync.exchange(context,next.server,next.fingerprint,next.token,"/api/health",null);
            next.moveEndpoint(context);
            return next;
        }
        throw new IOException("未找到已配对电脑。请确认 WallNote 已打开，且手机和电脑连接同一 Wi-Fi。");
    }
    static JSONObject pairRequest(Context context,Computer computer,String route,JSONObject body)throws Exception{
        return CalendarSync.exchange(context,computer.server,computer.fingerprint,null,route,body);
    }
}
