package com.waterlemongan.audiostreaming.utils;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import com.waterlemongan.audiostreaming.jlibrtp.*;

import java.io.IOException;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;


public class SoundSender implements RTPAppIntf  {
	private static final String TAG = "SoundSender";
	public RTPSession rtpSession = null;
	
	static int pktCount = 0;
	static int dataCount = 0;
	private String srcPath;
	private final int EXTERNAL_BUFFER_SIZE = 1024;
	private MediaCodec mediaCodec;
	private MediaExtractor mediaExtractor;
	private ByteBuffer[] inputBuffers;
	private ByteBuffer[] outputBuffers;
	private MediaCodec.BufferInfo bufferInfo;
	private boolean isPause = false;
	boolean local;

	public void setSrcPath(String srcPath) {
		this.srcPath = srcPath;
	}
	 
	
	public SoundSender(boolean isLocal)  {
		DatagramSocket rtpSocket = null;
		DatagramSocket rtcpSocket = null;
		
		try {
			rtpSocket = new DatagramSocket(13200);
			rtcpSocket = new DatagramSocket(13201);
		} catch (Exception e) {
			System.out.println("RTPSession failed to obtain port");
		}
		System.out.println(rtpSocket);
		System.out.println(rtcpSocket);
		
		rtpSession = new RTPSession(rtpSocket, rtcpSocket);
		rtpSession.RTPSessionRegister(this,null, null);
		System.out.println("CNAME: " + rtpSession.CNAME());
		this.local = isLocal;
	}
	

	public void receiveData(DataFrame dummy1, Participant dummy2) {
		// We don't expect any data.
	}
	
	public void userEvent(int type, Participant[] participant) {
		//Do nothing
	}
	
	public int frameSize(int payloadType) {
		return 1;
	}

	public void reversePause() {
		isPause = !isPause;
	}

	/**
	 * 输入为文件，初始化解码器
	 */
	public void initMediaDecode() {
		try {
			mediaExtractor=new MediaExtractor();//此类可分离视频文件的音轨和视频轨道
			mediaExtractor.setDataSource(srcPath);//媒体文件的位置
			for (int i = 0; i < mediaExtractor.getTrackCount(); i++) {//遍历媒体轨道 此处我们传入的是音频文件，所以也就只有一条轨道
				MediaFormat format = mediaExtractor.getTrackFormat(i);
				String mime = format.getString(MediaFormat.KEY_MIME);
				if (mime.startsWith("audio")) {//获取音频轨道
//                    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 200 * 1024);
					mediaExtractor.selectTrack(i);//选择此音频轨道
					mediaCodec = MediaCodec.createDecoderByType(mime);//创建Decode解码器
					mediaCodec.configure(format, null, null, 0);
					break;
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		if (mediaCodec == null) {
			Log.e(TAG, "create mediaCodec failed");
			return;
		}
		mediaCodec.start();//启动MediaCodec ，等待传入数据
		inputBuffers= mediaCodec.getInputBuffers();//MediaCodec在此ByteBuffer[]中获取输入数据
		outputBuffers= mediaCodec.getOutputBuffers();//MediaCodec将解码后的数据放到此ByteBuffer[]中 我们可以直接在这里面得到PCM数据
		bufferInfo=new MediaCodec.BufferInfo();//用于描述解码得到的byte[]数据的相关信息
		Log.e(TAG, "buffers:" + inputBuffers.length);
	}
	
	public void run() {
		if(RTPSession.rtpDebugLevel > 1) {
			System.out.println("-> Run()");
		}

		boolean inputEnd = false;
		boolean outputEnd= false;
		int pointer = 0;
		byte[] tmp = new byte[1024];
		ByteBuffer mBuffer = ByteBuffer.allocate(1024 * 10);
		final long kTimeOutUs = 10000;
		while (!outputEnd) {
			try {
				if (!inputEnd) {//输入到解码器 进行解码
					int inputBufIndex = mediaCodec.dequeueInputBuffer(kTimeOutUs);
					if (inputBufIndex >= 0) {
						ByteBuffer dstBuf = inputBuffers[inputBufIndex];

						int sampleSize = mediaExtractor.readSampleData(dstBuf, 0);//从分离器拿数据
						if (sampleSize < 0) {
							mediaCodec.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
							inputEnd = true;
						} else {
							long mediatime = mediaExtractor.getSampleTime();
//将数据送入解码器
							mediaCodec.queueInputBuffer(inputBufIndex, 0, sampleSize, mediatime, inputEnd ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
							mediaExtractor.advance();
						}
					}
				}
//从解码器输出
				int res = mediaCodec.dequeueOutputBuffer(bufferInfo, kTimeOutUs); //将数据从解码器拿出来
				if (res >= 0) {
					int outputBufIndex = res;
					ByteBuffer buf = outputBuffers[outputBufIndex];
					Log.d(TAG, "bufferInfo.size:" + String.valueOf(bufferInfo.size));
					final byte[] pcmData = new byte[bufferInfo.size];
					int size = bufferInfo.size;
					buf.get(pcmData);
					buf.clear();
					if (pcmData.length > 0) {
//对音频数据pcm进行输出
						mBuffer.put(pcmData, 0, size);
						pointer += size;
						Log.d(TAG, "pointer:" + String.valueOf(pointer));
						Log.d(TAG, "buffer:" + String.valueOf(mBuffer.position()));
						mBuffer.flip();
						while (mBuffer.remaining() >= 1024) {
							mBuffer.get(tmp, 0, 1024);
							Log.d(TAG, "buffer:" + String.valueOf(mBuffer.remaining()));
							pointer -= 1024;
							Log.d(TAG, "pointer:" + String.valueOf(pointer));
							rtpSession.sendData(tmp);
						}
						mBuffer.compact();
						//rtpSession.sendData(pcmData);
					}

					try {
						Thread.sleep(16);//多长时间刷新
					} catch (InterruptedException e) {
						e.printStackTrace();
						break;
					}
					while (isPause) {
						Log.d(TAG, "run: paused");
						//Thread.sleep(1000);
						//isPause=false;
					}
//告诉显示器释放并显示这个内容
					Log.d(TAG, "run: here");
					mediaCodec.releaseOutputBuffer(outputBufIndex, true);
				}

			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		if (mediaCodec != null) {
			mediaCodec.stop();
			mediaCodec.release();
			mediaCodec = null;
		}
		if (mediaExtractor != null) {
			mediaExtractor.release();
			mediaExtractor = null;
		}

		//try { Thread.sleep(200);} catch(Exception e) {}
		try{
		this.rtpSession.endSession();}
		catch (Exception e) {}
		
		//try { Thread.sleep(2000);} catch(Exception e) {}
		if(RTPSession.rtpDebugLevel > 1) {
			System.out.println("<- Run()");
		} 
	}

}
