package network;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.concurrent.Semaphore;
import java.util.zip.CRC32;

public class Client {

	static int data_size = 988;			// (checksum:8, seqNum:4, data<=988) Bytes : 1000 Bytes total
	static int win_size = 10;
	int segLength;
	static int timeoutVal = 500;		// 300ms until timeout
	static String dnsserver;
	static String dnstype;
	static String dnstarget;
	static String serverAddress;
	int base;					// base sequence number of window
	int nextSeqNum;				// next sequence number in window
	String data;
	int proxyPort;
	int rcvPort;
	Vector<byte[]> packetsList;	// list of generated packets
	Timer timer;				
	Semaphore s;				// guard CS for base, nextSeqNum
	boolean isTransferComplete;	// if receiver has completely received the file
	boolean http_udp;
	boolean dns;
	boolean proxyAck_rcvd;
	byte[] dataByte;
	
	public Client() {
		base = 0;
		nextSeqNum = 0;
		proxyPort = 10000;
		rcvPort = 20000;
		packetsList = new Vector<byte[]>(win_size);
		isTransferComplete = false;
		s = new Semaphore(1);
		http_udp = false;
		dns = false;
		proxyAck_rcvd = false;
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
				System.out.println("Client: Timeout!");
				nextSeqNum = base;	// resets nextSeqNum
				s.release();	/***** leave CS *****/
			} catch(InterruptedException e){
				e.printStackTrace();
			}
		}
	}
	public void sendandreceivedns (String in_data , int proxyport)throws Exception{
		  String sentence = in_data;
		  String modifiedSentence;
		  Socket clientSocket = new Socket("localhost", proxyport);
		  DataOutputStream outToServer = new DataOutputStream(clientSocket.getOutputStream());
		  BufferedReader inFromServer = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
		  outToServer.writeBytes(sentence + '\n');
		  modifiedSentence = inFromServer.readLine();
		  System.out.println("FROM PROXY: " + modifiedSentence);
		  clientSocket.close();	
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
	
	public byte[] copyOfRange(byte[] srcArr, int start, int end){
		int length = (end > srcArr.length)? srcArr.length-start: end-start;
		byte[] destArr = new byte[length];
		System.arraycopy(srcArr, start, destArr, 0, length);
		return destArr;
	}

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

			 System.out.println("Client: dst_port=" + dst_port + ", Data=" + data);
			 InetAddress dst_addr;
			 int i = 0;
			try{
				 dst_addr = InetAddress.getByName("127.0.0.1"); 
				 
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
									proxyAck_rcvd = true;
//									System.out.println("len<seg");
								}
								else{
//									if (dataBuffer.length < 0){
//										isFinalSeqNum = true;
//										out_data = generatePacket(nextSeqNum, new byte[0]);
//										isTransferComplete = true;
//										proxyAck_rcvd = true;
//										System.out.println("len<0");
//									}
//									else{
										System.out.println("len>seg");
										
//										byte[] dataBytes = copyOfRange(dataBuffer, 0, segLength);
//										out_data = generatePacket(nextSeqNum, dataBytes);
										
//										System.out.println("segLength:"+segLength+ " i:"+i);
										int seg = i*segLength;
//										System.out.println("len:"+dataBuffer.length + "i*segLength+1:"+seg);
										
										if(seg < dataBuffer.length){
										dataByte = copyOfRange(dataBuffer, seg, seg + segLength-1);
										out_data = generatePacket(nextSeqNum, dataByte);
//										System.out.println("buffer:"+ new String(dataByte, "UTF-8") );
										i++;
										}else{
											isFinalSeqNum = true;
											out_data = generatePacket(nextSeqNum, new byte[0]);
											isTransferComplete = true;
											proxyAck_rcvd = true;
											System.out.println("len<0");
										}
										
//									}
							
								}
						
								packetsList.add(out_data);	
							}
							
							// send the packet
							sk_out.send(new DatagramPacket(out_data, out_data.length, dst_addr, dst_port));
							System.out.println("Client: Sent seqNum " + nextSeqNum);
							
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
					System.out.println("Client: sk_out closed!");
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
//							System.out.println("rcv ack");
							while (!isTransferComplete) {
								sk_in.receive(in_pkt);
								int ackNum = decodePacket(in_data);
								System.out.println("Client: Received Ack " + ackNum);
								
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
									else if (ackNum == -2) {
										isTransferComplete = true;
										proxyAck_rcvd = true;
									}
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
							System.out.println("Client: sk_in closed!");
						}
					} catch (Exception e) {
						e.printStackTrace();
						System.exit(-1);
						}
					}
				}

		
	public void receive_pkt_send_ack(int sk1_dst_port, int sk2_dst_port){
		if(proxyAck_rcvd){
			int pkt_size = 1000;
			DatagramSocket sk1, sk2;
			System.out.println("Client: sk1_dst_port=" + sk1_dst_port + ", " + "sk2_dst_port=" + sk2_dst_port + ".");
			
			int prevSeqNum = -1;				// previous sequence number received in-order 
			int nextSeqNum = 0;					// next expected sequence number
			boolean isTransferComplete = false;	// (flag) if transfer is complete
			String receivedData = "";
			
			// create sockets
			try {
				sk1 = new DatagramSocket(sk1_dst_port);	// incoming channel
				sk2 = new DatagramSocket();				// outgoing channel
				System.out.println("Client: Listening");
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
							System.out.println("Client: Received sequence number: " + seqNum);
							
							// if packet received in order
							if (seqNum == nextSeqNum){
								// if final packet (no data), send teardown ack
								if (in_pkt.getLength() == 12 || in_pkt.getLength() < segLength){
									byte[] ackPkt = generateAckPkt(-2);	// construct teardown packet (ack -2)
									// send 20 acks in case last ack is not received by Sender (assures Sender teardown)
									for (int i=0; i<20; i++) sk2.send(new DatagramPacket(ackPkt, ackPkt.length, dst_addr, sk2_dst_port));
									isTransferComplete = true;			// set flag to true
									System.out.println("Client: All packets received!");
									continue;	// end listener
								}
								// else send ack
								else{
									byte[] ackPkt = generateAckPkt(seqNum);
									sk2.send(new DatagramPacket(ackPkt, ackPkt.length, dst_addr, sk2_dst_port));
									System.out.println("Receiver: Sent Ack " + seqNum);
								}
								byte [] dataB = copyOfRange(in_data, 12, in_pkt.getLength());
							  	receivedData = new String(dataB, "UTF-8"); 
								System.out.println("Client: Data rcvd: " + receivedData);
								nextSeqNum ++; 			// update nextSeqNum
								prevSeqNum = seqNum;	// update prevSeqNum
							}
							
							// if out of order packet received, send duplicate ack
							else{
								byte[] ackPkt = generateAckPkt(prevSeqNum);
								sk2.send(new DatagramPacket(ackPkt, ackPkt.length, dst_addr, sk2_dst_port));
								System.out.println("Client: Sent duplicate Ack " + prevSeqNum);
							}
						}
						
						// else packet is corrupted
						else{
							System.out.println("Client: Corrupt packet dropped");
							byte[] ackPkt = generateAckPkt(prevSeqNum);
							sk2.send(new DatagramPacket(ackPkt, ackPkt.length, dst_addr, sk2_dst_port));
							System.out.println("Client: Sent duplicate Ack " + prevSeqNum);
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
					System.exit(-1);
				} finally {
					sk1.close();
					sk2.close();
					System.out.println("Client: sk1 closed!");
					System.out.println("Client: sk2 closed!");
				}
			} catch (SocketException e1) {
				e1.printStackTrace();
			}
		}
	}
	
	public void RunSendAndReceiveThreads(String data){
		if(!proxyAck_rcvd){
//			System.out.println("send");
			DatagramSocket sk1, sk2;
			try {
				// create sockets
				sk1 = new DatagramSocket(20001);
				sk2 = new DatagramSocket();		
	
				// create threads to process data
				send_pkt s = new send_pkt(sk2,proxyPort,data);
				receive_ack r = new receive_ack(sk1);
				s.start();
				r.start();
				
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			}
		}	
	}
	
		public static void main(String[] args) throws Exception {
			// parse parameters
//			if (args.length != 3) {
//				System.err.println("Usage: java Sender sk1_dst_port, sk4_dst_port, data");
//				System.exit(-1);
//			}
//			else 
			Client client = new Client();
//				Scanner in =  new Scanner (System.in);
//				String data = in.nextLine();
//				String [] sdata = data.split(" ");
//				String [] gsdata = data.split("/");
//				if (gsdata[0].equals("GET")){
//					System.out.println("http");
//					client.http_udp = true;
//					String b = in.nextLine();
//					String dst_Add = in.nextLine();
//					serverAddress = dst_Add.split(":")[1];
//				while(true){
					client.RunSendAndReceiveThreads("012345678998765432100123456789");	

					client.receive_pkt_send_ack(client.rcvPort,client.proxyPort);
//				}
//				}			
//				else if (sdata[0].split("=")[0].equals("type")){
////					System.out.println("dns");
//					client.dns = true;
//					dnstype = sdata[0].split("=")[1];
//					dnsserver = sdata[1].split("=")[1];
//					dnstarget = sdata[2].split("=")[1];
//					System.out.println(dnstype +"---" + dnsserver + "---" + dnstarget ); 
//					client.sendandreceivedns(data, client.proxyPort);
//				}
//				else 
//					System.out.println("error");
//				
	}
	}



	