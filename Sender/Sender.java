
import java.io.FileInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Sender {

	public static void main(String[] args) throws Exception {

		String rcvIP = args[0];
		int rcvDataPort = Integer.parseInt(args[1]);
		int senderAckPort = Integer.parseInt(args[2]);
		String inputFile = args[3];
		int timeout = Integer.parseInt(args[4]);

		boolean useGBN = args.length == 6;
		int windowSize = useGBN ? Integer.parseInt(args[5]) : 1;

		InetAddress receiverAddr = InetAddress.getByName(rcvIP);

		DatagramSocket socket = new DatagramSocket(senderAckPort);
		socket.setSoTimeout(timeout);

		FileInputStream fileIn = new FileInputStream(inputFile);

		List<byte[]> fileChunks = readFileChunks(fileIn);

		long startTime = System.currentTimeMillis();

		sendSOT(socket, receiverAddr, rcvDataPort);

		if (useGBN) {
			sendGBN(socket, receiverAddr, rcvDataPort, fileChunks, windowSize);
		} else {
			sendStopAndWait(socket, receiverAddr, rcvDataPort, fileChunks);
		}

		sendEOT(socket, receiverAddr, rcvDataPort, fileChunks.size());

		long endTime = System.currentTimeMillis();

		double seconds = (endTime - startTime) / 1000.0;

		System.out.println("Total Transmission Time: " + seconds + " seconds");

		fileIn.close();
		socket.close();
	}

	private static List<byte[]> readFileChunks(FileInputStream fileIn) throws Exception {

		List<byte[]> chunks = new ArrayList<>();

		byte[] buffer = new byte[DSPacket.MAX_PAYLOAD_SIZE];

		int bytesRead;

		while ((bytesRead = fileIn.read(buffer)) != -1) {

			byte[] data = Arrays.copyOf(buffer, bytesRead);

			chunks.add(data);
		}

		return chunks;
	}

	private static void sendSOT(DatagramSocket socket, InetAddress addr, int port) throws Exception {

		DSPacket sot = new DSPacket(DSPacket.TYPE_SOT, 0, new byte[0]);

		sendPacket(socket, addr, port, sot);

		System.out.println("Sent SOT");

		waitForACK(socket, 0);

		System.out.println("Received ACK for SOT");
	}

	private static void sendStopAndWait(DatagramSocket socket, InetAddress addr, int port, List<byte[]> chunks)
			throws Exception {

		int seq = 1;

		for (byte[] data : chunks) {

			int timeoutCount = 0;

			boolean acked = false;

			while (!acked) {

				DSPacket packet = new DSPacket(DSPacket.TYPE_DATA, seq, data);

				sendPacket(socket, addr, port, packet);

				System.out.println("Sent DATA seq=" + seq);

				try {

					int ack = waitForACK(socket, seq);

					if (ack == seq) {
						

						acked = true;
						seq = (seq + 1) % 128;
					}

				} catch (SocketTimeoutException e) {

					timeoutCount++;

                    System.out.println("Timeout for seq=" + packet.getSeqNum());

                    if (timeoutCount >= 3) {
                        System.out.println("Unable to transfer file");
                        return;
					}
				}
			}
		}
	}

	private static void sendGBN(DatagramSocket socket, InetAddress addr, int port, List<byte[]> chunks, int windowSize)
			throws Exception {

		int base = 1;
		int nextSeq = 1;
		int timeoutCount = 0;

		Map<Integer, DSPacket> window = new HashMap<>();

		while (base <= chunks.size()) {

			while (nextSeq < base + windowSize && nextSeq <= chunks.size()) {

				DSPacket packet = new DSPacket(DSPacket.TYPE_DATA, nextSeq % 128, chunks.get(nextSeq - 1));

				sendPacket(socket, addr, port, packet);

				System.out.println("Sent DATA seq=" + (nextSeq % 128));

				window.put(nextSeq, packet);

				nextSeq++;
			}

			try {

				int ack = waitForACK(socket, nextSeq);
				System.out.println("Received Ack=" + ack);

                if (ack >= base && ack < nextSeq) {

                    base = ack + 1;

                    timeoutCount = 0;

                    //System.out.println("Window slides to base=" + base);
                }

			} catch (SocketTimeoutException e) {
				//Changed from origin
				timeoutCount++;

				System.out.println("Timeout. Resending window...");

				if (timeoutCount >= 3) {

					System.out.println("Unable to transfer file");

					return;
				}

				for (int i = base; i < nextSeq; i++) {
					
					DSPacket p = window.get(i);
					if (p != null) {
						sendPacket(socket, addr, port, p);
						System.out.println("Resent seq=" + p.getSeqNum());

					}
				}
			}
		}
	}

	private static void sendEOT(DatagramSocket socket, InetAddress addr, int port, int totalPackets) throws Exception {

		int seq = (totalPackets + 1) % 128;

		DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, seq, new byte[0]);

		sendPacket(socket, addr, port, eot);

		System.out.println("Sent EOT");

		waitForACK(socket, seq);

		System.out.println("Received ACK for EOT");
	}

	private static void sendPacket(DatagramSocket socket, InetAddress addr, int port, DSPacket packet)
			throws Exception {

		byte[] data = packet.toBytes();

		DatagramPacket dp = new DatagramPacket(data, data.length, addr, port);

		socket.send(dp);
	}

	private static int waitForACK(DatagramSocket socket, int expected) throws Exception {

		byte[] buffer = new byte[128];

		DatagramPacket dp = new DatagramPacket(buffer, buffer.length);

		socket.receive(dp);

		DSPacket ack = new DSPacket(dp.getData());

		int seq = ack.getSeqNum();

		return seq;
	}
}