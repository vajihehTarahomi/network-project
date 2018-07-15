package network;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Scanner;
import java.io.File;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.concurrent.Semaphore;
import java.util.zip.CRC32;


public class Proxy {
    public static boolean tcp_udp = false;
    public static boolean udp_tcp = false;
    public static String dst_IP;
    public static int dst_port;
    public static int serverport;
    public static String serverAddress;
	static int data_size = 988;			// (checksum:8, seqNum:4, data<=988) Bytes : 1000 Bytes total
	static int win_size = 10;
	static int timeoutVal = 500;		// 300ms until timeout
	int segLength = 100;
	int base;					// base sequence number of window
	int nextSeqNum;				// next sequence number in window
	String data;
	int proxyPort;
	int rcvPort;
	Vector<byte[]> packetsList;	// list of generated packets
	Timer timer;				
	Semaphore s;				// guard CS for base, nextSeqNum
	boolean isTransferComplete;	// if receiver has completely received the file
	boolean start_send_pkt;
	byte [] dataB;
	
	public Proxy() {
		base = 0;
		nextSeqNum = 0;
		proxyPort = 10000;
		rcvPort = 20000;
		packetsList = new Vector<byte[]>(win_size);
		isTransferComplete = false;
		start_send_pkt = false;
		s = new Semaphore(1);
	}
	// to start or stop the timer
	public void setTimer(boolean isNewTimer){
		if (timer != null) timer.cancel();
		if (isNewTimer){
			timer = new Timer();
			timer.schedule(new Timeout(), timeoutVal);
		}
	}
	// Timeout task
	public class Timeout extends TimerTask{
		public void run(){
			try{
				s.acquire();	/***** enter CS *****/
				System.out.println("Proxy: Timeout!");
				nextSeqNum = base;	// resets nextSeqNum
				s.release();	/***** leave CS *****/
			} catch(InterruptedException e){
				e.printStackTrace();
			}
		}
	}
	
	// constructs the packet prepended with header information
	public byte[] generatePacket(int seqNum, byte[] dataBytes){
		byte[] seqNumBytes = ByteBuffer.allocate(4).putInt(seqNum).array(); 				// Seq num (4 bytes)
		
		// generate checksum 
		CRC32 checksum = new CRC32();
		checksum.update(seqNumBytes);
		checksum.update(dataBytes);
		byte[] checksumBytes = ByteBuffer.allocate(8).putLong(checksum.getValue()).array();	// checksum (8 bytes)
		
		// generate packet
		ByteBuffer pktBuf = ByteBuffer.allocate(8 + 4 + dataBytes.length);
		pktBuf.put(checksumBytes);
		pktBuf.put(seqNumBytes);
		pktBuf.put(dataBytes);
		return pktBuf.array();
	}
	
	int decodePacket(byte[] pkt){
		byte[] received_checksumBytes = copyOfRange(pkt, 0, 8);
		byte[] ackNumBytes = copyOfRange(pkt, 8, 12);
		CRC32 checksum = new CRC32();
		checksum.update(ackNumBytes);
		byte[] calculated_checksumBytes = ByteBuffer.allocate(8).putLong(checksum.getValue()).array();// checksum (8 bytes)
		if (Arrays.equals(received_checksumBytes, calculated_checksumBytes)) return ByteBuffer.wrap(ackNumBytes).getInt();
		else return -1;
	}
public class send_pkt extends Thread{
		
		DatagramSocket sk_out;
		int dst_port; 
		String data;
		
		public send_pkt(DatagramSocket sk_out, int dst_port, String data){
			this.sk_out = sk_out;
			this.dst_port = dst_port;
			this.data = data;
		}
		
		public void run(){
			System.out.println("Proxy: dst_port=" + dst_port + ", Data=" + data);
			 InetAddress dst_addr;
			try{
				 dst_addr = InetAddress.getByName("127.0.0.1"); // resolve dst_addr
				 
				try {
					// while there are still packets yet to be received by receiver
					while (!isTransferComplete){
						// send packets if window is not yet full
						if (nextSeqNum < base + win_size){
							
							s.acquire();	/***** enter CS *****/
							if (base == nextSeqNum) setTimer(true);	// if first packet of window, start timer
							
							byte[] out_data = new byte[10];
							boolean isFinalSeqNum = false;
							
							// if packet is in packetsList, retrieve from list
							if (nextSeqNum < packetsList.size()){
								out_data = packetsList.get(nextSeqNum);
							}
						
							// else construct packet and add to list
							else{

								byte[] dataBuffer = new byte[data_size]; 
								dataBuffer = data.getBytes();
								segLength = 100;
								if (dataBuffer.length < segLength){
									out_data = generatePacket(nextSeqNum, dataBuffer);
									isFinalSeqNum = true;
									isTransferComplete = true;
								}
								else{
									if (dataBuffer.length < 0){
										isFinalSeqNum = true;
										out_data = generatePacket(nextSeqNum, new byte[0]);
									}
									else{
										byte[] dataBytes = copyOfRange(dataBuffer, 0, segLength);
										out_data = generatePacket(nextSeqNum, dataBytes);
										dataBuffer = copyOfRange(dataBuffer, 0, segLength);
									}
							
								}
								packetsList.add(out_data);	// add to packetsList
							}
							
							// send the packet
							sk_out.send(new DatagramPacket(out_data, out_data.length, dst_addr, dst_port));
							System.out.println("Proxy: Sent seqNum " + nextSeqNum);
							
							// update nextSeqNum if currently not at FinalSeqNum
							if (!isFinalSeqNum) nextSeqNum++;
							s.release();	/***** leave CS *****/
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					setTimer(false);	// close timer
					sk_out.close();		// close outgoing socket
					System.out.println("Proxy: sk_out closed!");
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			}
		}
	}	
		// receiving process (updates base)
	public class receive_ack extends Thread{
		
		DatagramSocket sk_in;
		
		public receive_ack(DatagramSocket sk_in){
			this.sk_in = sk_in;
		}
		
		public void run(){
			try {
				byte[] in_data = new byte[12];	// ack packet with no data
				DatagramPacket in_pkt = new DatagramPacket(in_data,	in_data.length);
				try {
					// while there are still packets yet to be received by receiver
					while (!isTransferComplete) {
						
						sk_in.receive(in_pkt);
						int ackNum = decodePacket(in_data);
						System.out.println("Proxy: Received Ack " + ackNum);
						
						// if ack is not corrupted
						if (ackNum != -1){
							// if duplicate ack
							if (base == ackNum + 1){
								s.acquire();	/***** enter CS *****/
								setTimer(false);		// off timer
								nextSeqNum = base;		// resets nextSeqNum
								s.release();	/***** leave CS *****/
							}
							// else if teardown ack
							else if (ackNum == -2) isTransferComplete = true;
							// else normal ack
							else{
								base = ackNum++;	// update base number
								s.acquire();	/***** enter CS *****/
								if (base == nextSeqNum) setTimer(false);	// if no more unacknowledged packets in pipe, off timer
								else setTimer(true);						// else packet acknowledged, restart timer
								s.release();	/***** leave CS *****/
							}
						}
						// else if ack corrupted, do nothing
					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					sk_in.close();
					System.out.println("Proxy: sk_in closed!");
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			}
		}
	}

	public void receive_pkt_send_ack(int sk1_dst_port, int sk2_dst_port){
		if(!start_send_pkt){
			int pkt_size = 1000;
			DatagramSocket sk1, sk2;
			System.out.println("Proxy: sk1_dst_port=" + sk1_dst_port + ", " + "sk2_dst_port=" + sk2_dst_port + ".");
//			int i = 0;
//			int k =1;
			int prevSeqNum = -1;				// previous sequence number received in-order 
			int nextSeqNum = 0;					// next expected sequence number
			boolean isTransferComplete = false;	// (flag) if transfer is complete
			String receivedData = "";
			
			// create sockets
			try {
				sk1 = new DatagramSocket(sk1_dst_port);	// incoming channel
				sk2 = new DatagramSocket();				// outgoing channel
				System.out.println("Proxy: Listening");
				try {
					byte[] in_data = new byte[pkt_size];									// message data in packet
					DatagramPacket in_pkt = new DatagramPacket(in_data,	in_data.length);	// incoming packet
					InetAddress dst_addr = InetAddress.getByName("127.0.0.1");
					
					// listen on sk1_dst_port
					while (!isTransferComplete) {
						// receive packet
						sk1.receive(in_pkt);
	
						byte[] received_checksum = copyOfRange(in_data, 0, 8);
						CRC32 checksum = new CRC32();
						checksum.update(copyOfRange(in_data, 8, in_pkt.getLength()));
						byte[] calculated_checksum = ByteBuffer.allocate(8).putLong(checksum.getValue()).array();
						
						// if packet is not corrupted
						if (Arrays.equals(received_checksum, calculated_checksum)){
							int seqNum = ByteBuffer.wrap(copyOfRange(in_data, 8, 12)).getInt();
							System.out.println("Proxy: Received sequence number: " + seqNum);
							
							// if packet received in order
							if (seqNum == nextSeqNum){
								
								if (in_pkt.getLength() == 12){
									byte[] ackPkt = generateAckPkt(-2);	// construct teardown packet (ack -2)
									// send 20 acks in case last ack is not received by Sender (assures Sender teardown)
									for (int j=0; j<20; j++) sk2.send(new DatagramPacket(ackPkt, ackPkt.length, dst_addr, sk2_dst_port));
									isTransferComplete = true;			// set flag to true
									start_send_pkt = true;
									System.out.println("Proxy: All packets received!");
									receivedData = new String(dataB, "UTF-8"); 
									System.out.println("Proxy: Data rcvd: "+ receivedData);
	//								continue;	// end listener
								}
								// else send ack
								else{
									byte[] ackPkt = generateAckPkt(seqNum);
									sk2.send(new DatagramPacket(ackPkt, ackPkt.length, dst_addr, sk2_dst_port));
									System.out.println("Proxy: Sent Ack " + seqNum);
								}
								if (in_pkt.getLength() < segLength && in_pkt.getLength() != 12){
									isTransferComplete = true;			// set flag to true
									start_send_pkt = true;
									System.out.println("Proxy:  packet received!");
									byte [] dataB = copyOfRange(in_data, 12, in_pkt.getLength());
									receivedData = new String(dataB, "UTF-8"); 
									System.out.println("Proxy: Data rcvd: "+ receivedData);
								}else{
								
								dataB = copyOfRange(in_data, 12, + 12 + segLength-1);
								String rData = new String(dataB, "UTF-8"); 
								receivedData += rData;
								System.out.println("Proxy: Data ta inja rcvd: "+ receivedData);
//								i++;
//								k++;
								nextSeqNum ++; 			// update nextSeqNum
								prevSeqNum = seqNum;	// update prevSeqNum
								}
							}
							
							// if out of order packet received, send duplicate ack
							else{
								byte[] ackPkt = generateAckPkt(prevSeqNum);
								sk2.send(new DatagramPacket(ackPkt, ackPkt.length, dst_addr, sk2_dst_port));
								System.out.println("Proxy: Sent duplicate Ack " + prevSeqNum);
							}
						}
						
						// else packet is corrupted
						else{
							System.out.println("Proxy: Corrupt packet dropped");
							byte[] ackPkt = generateAckPkt(prevSeqNum);
							sk2.send(new DatagramPacket(ackPkt, ackPkt.length, dst_addr, sk2_dst_port));
							System.out.println("Proxy: Sent duplicate Ack " + prevSeqNum);
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
					System.exit(-1);
				} finally {
					sk1.close();
					sk2.close();
					System.out.println("Proxy: sk1 closed!");
					System.out.println("Proxy: sk2 closed!");
				}
			} catch (SocketException e1) {
				e1.printStackTrace();
			}
		}
	}
	
	// generate Ack packet
	public byte[] generateAckPkt(int ackNum){
		byte[] ackNumBytes = ByteBuffer.allocate(4).putInt(ackNum).array();
		// calculate checksum
		CRC32 checksum = new CRC32();
		checksum.update(ackNumBytes);
		// construct Ack packet
		ByteBuffer pktBuf = ByteBuffer.allocate(12);
		pktBuf.put(ByteBuffer.allocate(8).putLong(checksum.getValue()).array());
		pktBuf.put(ackNumBytes);
		return pktBuf.array();
	}
	
	public byte[] copyOfRange(byte[] srcArr, int start, int end){
		int length = (end > srcArr.length)? srcArr.length-start: end-start;
		byte[] destArr = new byte[length];
		System.arraycopy(srcArr, start, destArr, 0, length);
		return destArr;
	}
	
	public void RunSendAndReceiveThreads(String data){
		if(start_send_pkt){
			DatagramSocket sk1, sk2;
			try {
				// create sockets
				sk1 = new DatagramSocket(10000);
				sk2 = new DatagramSocket();		
	
				// create threads to process data
				send_pkt s = new send_pkt(sk2,rcvPort,data);
				receive_ack r = new receive_ack(sk1);
				s.start();
				r.start();
				
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			}	
		}
	}
	public void proxytcpsend (String in_data , int serverport ,String Address )throws Exception{
		  String sentence = in_data;
		  String modifiedSentence;
		  Socket clientSocket = new Socket(Address, serverport);
		  DataOutputStream outToServer = new DataOutputStream(clientSocket.getOutputStream());
		  BufferedReader inFromServer = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
		  outToServer.writeBytes(sentence + '\n');
		  modifiedSentence = inFromServer.readLine();
		  System.out.println("FROM SERVER: " + modifiedSentence);
		  int status = Integer.parseInt(modifiedSentence.split(" ")[1]);
		  switch(status){
		  case 400:
			  System.out.println("Bad Request");
			  break;
		  case 404:
			  System.out.println("Not Found");
			  break;
		  case 301|302:
			  String NewAddress = modifiedSentence.split("\n")[2].split(":")[1];
			  proxytcpsend(sentence, serverport, NewAddress);
			  break;
		  }
		  clientSocket.close();	
	}	
	public void proxytcpreceive() throws IOException{
		if(tcp_udp){
			  String clientSentence;
			  String capitalizedSentence;
			  ServerSocket welcomeSocket = new ServerSocket(1000);

			  while (true) {
			   Socket connectionSocket = welcomeSocket.accept();
			   BufferedReader inFromClient =
			    new BufferedReader(new InputStreamReader(connectionSocket.getInputStream()));
			   DataOutputStream outToClient = new DataOutputStream(connectionSocket.getOutputStream());
			   clientSentence = inFromClient.readLine();
			   System.out.println("Received: " + clientSentence);
			   capitalizedSentence = clientSentence.toUpperCase() + '\n';
			   outToClient.writeBytes(capitalizedSentence);
			  }
		}
	}
	public static void main(String[] args) throws Exception{
		Proxy proxy = new Proxy();
//		Scanner in =  new Scanner (System.in);
//		String data = in.nextLine();
//		String [] sdata = data.split(" ");
//		if(sdata[0].equals("proxy")){
//			if(sdata[1].split("=")[0].equals("-s") && sdata[2].split("=")[0].equals("-d")){
//				if(sdata[1].split("=")[1].split(":")[0].equals("udp") && sdata[2].split("=")[1].equals("tcp")){
//					udp_tcp = true;
//					String message = "GET /hello.htm HTTP/1.1";
//					serverport = 80;
//					serverAddress = "tutorialspoint.com";
//				while(true){
					proxy.receive_pkt_send_ack(10000, 20001);

					proxy.RunSendAndReceiveThreads("msg from proxy");
//				}
//					new Proxy().proxytcpsend(message, serverport , serverAddress);
//				}
//				else if(sdata[1].split("=")[1].split(":")[0].equals("tcp") && sdata[2].split("=")[1].equals("udp")){
//					tcp_udp = true;
//					new Proxy().proxytcpreceive();
//					new Proxy().RunSendAndReceiveThreads("msg from proxy");
//				}
//				dst_IP = sdata[1].split("=")[1].split(":")[1];
//				System.out.println(dst_IP);
//				dst_port = Integer.parseInt(sdata[1].split("=")[1].split(":")[2]);
//				System.out.println(dst_port);
//			}
//		}
//		else
//			System.out.println("error");
//		for (int i=0 ; i<sdata.length ; i++){
//			System.out.println(sdata[i]);
//		}
	}
}
