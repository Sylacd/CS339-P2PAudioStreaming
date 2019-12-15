package com.waterlemongan.audiostreaming;

import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Timer;
import java.util.TimerTask;

class Client {
    private InetAddress serverAddr;
    private NetworkUtil network;
    private Timer timer;
    private Timer clientTimer;

    public Client(InetAddress address, final EventListener listener) {
        serverAddr = address;

        network = new NetworkUtil();
        timer = new Timer();
        clientTimer = new Timer();

        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    if (serverAddr.isReachable(1000)) {
                        NetworkUtil.sendMessage(serverAddr, "server?");
                        clientTimer.schedule(new TimerTask() {
                            @Override
                            public void run() {
                                listener.onServerDisconnect();
                            }
                        }, 1000);
                    } else {
                        listener.onServerDisconnect();
                    }
                } catch (IOException e) {
                    log("check server alive failed");
                    listener.onServerDisconnect();
                    e.printStackTrace();
                }
            }
        }, 30000, 30000);

        network.receiveMessage(new NetworkUtil.MessageListener() {
            @Override
            public void onReceiveMessage(InetAddress address, String msg) {
                switch (msg) {
                    case "server?":
                        NetworkUtil.sendMessage(address, "serverNo");
                        break;
                    case "serverNo":
                    case "notServe":
                        if (address.equals(serverAddr)) {
                            listener.onServerDisconnect();
                        }
                        break;
                    case "playOk":
                        listener.onPlayOk();
                        break;
                    case "serverYes":
                        clientTimer.cancel();
                        break;
                    default:
                        log("unknown control message: " + msg);
                        break;
                }
            }
        });
    }

    public void sendPlay() {
        NetworkUtil.sendMessage(serverAddr, "play");
    }

    public void stop() {
        network.stop();
        timer.cancel();
        clientTimer.cancel();
    }

    public String getServerName() {
        return serverAddr.getHostName();
    }

    public String getServerAddress() {
        return serverAddr.getHostAddress();
    }

    private static void log(String logMsg) {
        Log.d("Client", logMsg);
    }

    public static void searchServer(final SearchListener listener) {
        final NetworkUtil network = new NetworkUtil();
        final Timer timer = new Timer();

        NetworkUtil.sendBroadcastMessage("client");
        network.receiveMessage(new NetworkUtil.MessageListener() {
            @Override
            public void onReceiveMessage(InetAddress address, String msg) {
                if (msg.equals("server")) {
                    network.stop();
                    timer.cancel();
                    listener.onServerFound(address);
                } else if (msg.equals("server?")) {
                    NetworkUtil.sendMessage(address, "serverNo");
                }
            }
        });
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                listener.onServerNotFound();
                NetworkUtil.sendBroadcastMessage("client");
            }
        }, 1000, 30000);
    }


    public interface EventListener {
        void onServerDisconnect();

        void onPlayOk();
    }

    public interface SearchListener {
        void onServerNotFound();

        void onServerFound(InetAddress address);
    }
}
