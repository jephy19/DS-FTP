import java.net.*;
import java.io.*;

public class Receiver {

    static final int MAX_SEQ = 128;

    public static void main(String[] args) throws Exception {

        String sender_ip = args[0];
        int sender_ack_port = Integer.parseInt(args[1]);
        int rcv_data_port = Integer.parseInt(args[2]);
        String output_file = args[3];
        int RN = Integer.parseInt(args[4]);

        DatagramSocket socket = new DatagramSocket(rcv_data_port);
        System.out.println("Receiver started and Waiting for packets..."); //JST FOR TESTING--MOVE THIS AFTER HANDSHAKE ONCE SENDER IS IMPLEMENTED

        InetAddress senderAddr = InetAddress.getByName(sender_ip);


        boolean[] received = new boolean[MAX_SEQ];
        DSPacket[] buffer = new DSPacket[MAX_SEQ];

        int expectedSeq = 1;

        int ackCount = 0;

        /* ---------- HANDSHAKE ---------- */

        DSPacket sot = receivePacket(socket);

        if (sot.getType() != DSPacket.TYPE_SOT) { // check if the packet is a SOT packet before sending sot_ack
            System.out.println("Expected SOT packet");
            socket.close();
            return;
        }

        sendAck(socket, senderAddr, sender_ack_port, 0, RN, ++ackCount);
        FileOutputStream fos = new FileOutputStream(output_file);
        

        /* ---------- DATA TRANSFER ---------- */

        while (true) {

            DSPacket packet = receivePacket(socket);

            int seq = packet.getSeqNum();

            if (packet.getType() == DSPacket.TYPE_EOT) {

                sendAck(socket, senderAddr, sender_ack_port, seq, RN, ++ackCount);
                break;
            }

            if (!received[seq]) {

                received[seq] = true;
                buffer[seq] = packet;
            }

            while (received[expectedSeq]) {

                DSPacket p = buffer[expectedSeq];

                fos.write(p.getPayload(), 0, p.getLength());

                received[expectedSeq] = false;

                expectedSeq = (expectedSeq + 1) % MAX_SEQ;
            }

            int ackSeq = (expectedSeq - 1 + MAX_SEQ) % MAX_SEQ;

            sendAck(socket, senderAddr, sender_ack_port, ackSeq, RN, ++ackCount);
        }

        fos.close();
        socket.close();
    }

    static DSPacket receivePacket(DatagramSocket socket) throws Exception {

        byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];

        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        socket.receive(dp);

        DSPacket packet = new DSPacket(buf);

        System.out.println("Received packet seq=" + packet.getSeqNum());

        return packet;
    }

    static void sendAck(DatagramSocket socket,
                        InetAddress addr,
                        int port,
                        int seq,
                        int rn,
                        int ackCount) throws Exception {

        if (ChaosEngine.shouldDrop(ackCount, rn)) {
            System.out.println("ACK " + seq + " DROPPED");
            return;
        }

        DSPacket ack = new DSPacket(DSPacket.TYPE_ACK, seq, null);

        byte[] data = ack.toBytes();

        DatagramPacket dp =
                new DatagramPacket(data, data.length, addr, port);

        socket.send(dp);

        System.out.println("Sent ACK=" + seq);
    }
}