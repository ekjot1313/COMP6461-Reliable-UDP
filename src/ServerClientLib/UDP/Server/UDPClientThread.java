package ServerClientLib.UDP.Server;

import ServerClientLib.UDP.MultiPacketHandler;
import ServerClientLib.UDP.Packet;
import ServerClientLib.dao.Message;
import ServerClientLib.dao.Reply;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.nio.channels.DatagramChannel;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class UDPClientThread extends Thread {

    private Boolean requestMade = false;
    private SocketAddress routerAddr;
    private DatagramChannel channel;
    private BlockingQueue<Reply> inbox = new LinkedBlockingQueue<>();
    private BlockingQueue<Message> outbox;
    private final boolean VERBOSE;
    private volatile static int numberOfClients = 0;
    private MultiPacketHandler pktHandler = new MultiPacketHandler();

    InetAddress clientAddr;
    Integer clientPort;

    UDPClientThread(DatagramChannel channel, SocketAddress routerAddr, BlockingQueue<Message> outbox, boolean VERBOSE) {
        this.channel = channel;
        this.routerAddr = routerAddr;
        this.outbox = outbox;
        this.VERBOSE = VERBOSE;
        numberOfClients++;
        if (VERBOSE) {
            System.out.println("Client connected: " + clientAddr);
            System.out.println("Total clients: " + numberOfClients);
        }

    }

    @Override
    public void run() {
        try {
            handleClient();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void handleClient() throws IOException, InterruptedException {
        while (true) {
            if (pktHandler.allPacketsReceived()) {
                String body = pktHandler.mergeAllPackets();
                handleInput(body);
                break;
            }
        }


    }

    private void handleInput(String body) throws IOException {
        Message msg = new Message(body, inbox);
        outbox.add(msg);
        requestMade = true;
        while (requestMade) {
            if (!inbox.isEmpty()) {
                Reply reply = inbox.poll();
                handleOutput(formatOutput(reply));
                requestMade = false;
                if (VERBOSE)
                    System.out.println("Reply sent to " + clientAddr);
            }
        }
        numberOfClients--;

        if (VERBOSE) {
            System.out.println("Client disconnected: " + clientAddr);
            System.out.println("Total clients: " + numberOfClients);
        }
    }

    private void handleOutput(String body) throws IOException {
        ArrayList<String> payloads = pktHandler.generatePayloads(body);
        long seqNum = 1L;
        for (int i = 0; i < payloads.size(); i++) {
            String payload = payloads.get(i);

            Packet p = new Packet.Builder()
                    .setType((i <payloads.size() - 1) ? 0 : 2)
                    .setSequenceNumber(seqNum++)
                    .setPortNumber(clientPort)
                    .setPeerAddress(clientAddr)
                    .setPayload(payload.getBytes())
                    .create();
            channel.send(p.toBuffer(), routerAddr);
            System.out.println("Reply Packet #" + (seqNum-1) + " sent to " + routerAddr);
        }

    }


    private String formatOutput(Reply reply) {
        String head = "";
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss O", Locale.ENGLISH);

        String body = reply.getBody();
        if (reply.getStatus() == 500) {
            head += "HTTP/1.1 500 Internal Server Error\r\n";
            head += "Date: " + formatter.format(ZonedDateTime.now(ZoneOffset.UTC)) + "\r\n";
            body = "Internal Server Error";
        } else if (reply.getStatus() == 404) {
            head += "HTTP/1." +
                    "1 404 Not Found\r\n";
            head += "Date: " + formatter.format(ZonedDateTime.now(ZoneOffset.UTC)) + "\r\n";
            body = "File not present in the current directory";
        } else if (reply.getStatus() == 400) {
            head += "HTTP/1.1 400 Bad Request\r\n";
            head += "Date: " + formatter.format(ZonedDateTime.now(ZoneOffset.UTC)) + "\r\n";
            body = "Server can not understand request";
        } else if (reply.getStatus() == 200) {
            head += "HTTP/1.1 200 OK\r\n";
            head += "Date: " + formatter.format(ZonedDateTime.now(ZoneOffset.UTC)) + "\r\n";

            head += "Content-Type: " + reply.getContentType() + "\r\n";
            head += "Content-Disposition: ";
            if (reply.getContentType() != null) {
                if (reply.getContentType().startsWith("text/")) {
                    head += "inline\r\n";
                } else {
                    head += "attachment\r\n";
                }
            } else
                head += "null\r\n";

        } else if (reply.getStatus() == 201) {
            head += "HTTP/1.1 200 OK\r\n";
            head += "Date: " + formatter.format(ZonedDateTime.now(ZoneOffset.UTC)) + "\r\n";
            body = "File updated Successfully";
        }

        head += "Content-Length: " + body.length() + "\r\n";
        head += "Connection: Close\r\n";
        head += "Server: Localhost\r\n";

        body = head + "\r\n" + body;

        return body;
    }


    public void addNewPacket(Packet packet) {
        if (VERBOSE)
            System.out.println("Got a new packet from " + clientAddr);
        clientAddr = packet.getPeerAddress();
        clientPort = packet.getPeerPort();

        pktHandler.addNewPacket(packet);
    }
}