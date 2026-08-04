package com.englishlistener.player
import android.media.MediaCodec; import android.media.MediaFormat; import android.util.Log
import kotlinx.coroutines.*; import java.io.BufferedInputStream
import java.net.HttpURLConnection; import java.net.URL; import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
class AudioCaptureProcessor{companion object{private const val TAG="AudioCapture";private const val TARGET_SR=16000}
private val listeners=CopyOnWriteArrayList<(FloatArray)->Unit>()
private var job:Job?=null
private val scope=CoroutineScope(Dispatchers.IO+SupervisorJob())
fun addListener(l:(FloatArray)->Unit){listeners.add(l)}
fun removeListener(l:(FloatArray)->Unit){listeners.remove(l)}
fun start(streamUrl:String){stop()
job=scope.launch{try{
val conn=URL(streamUrl).openConnection() as HttpURLConnection
conn.connectTimeout=10000;conn.readTimeout=60000;conn.setRequestProperty("User-Agent","EnglishListener/1.0");conn.setRequestProperty("Icy-MetaData","1");conn.instanceFollowRedirects=true
if(conn.responseCode!=200){Log.e(TAG,"HTTP ${conn.responseCode}");return@launch}
val icyMetaInt=conn.getHeaderField("Icy-MetaInt")?.toIntOrNull()?:0
val input=BufferedInputStream(conn.inputStream);var codec:MediaCodec?=null
val buf=ByteArray(8192);var bytesRead:Int;var totalRead=0;val initialChunk=ByteArray(524288);var initLen=0
while(isActive&&initLen<524288){bytesRead=input.read(buf);if(bytesRead<=0)break
if(icyMetaInt>0&&totalRead>0&&totalRead%131072==0){val metaLen=input.read()*16;if(metaLen>0){val skip=ByteArray(metaLen);input.read(skip)}}
System.arraycopy(buf,0,initialChunk,initLen,bytesRead);initLen+=bytesRead;totalRead+=bytesRead}
if(initLen==0){input.close();conn.disconnect();return@launch}
val mime=if((initialChunk[0].toInt()and 0xFF)==0xFF&&(initialChunk[1].toInt()and 0xE0)==0xE0)"audio/mpeg"else"audio/mpeg";Log.i(TAG,"mime: $mime")
codec=try{val c=MediaCodec.createDecoderByType(mime);c.configure(MediaFormat.createAudioFormat(mime,44100,2),null,null,0);c.start();c}catch(e:Exception){Log.e(TAG,"codec fail",e);input.close();return@launch}
feed(codec,ByteBuffer.wrap(initialChunk,0,initLen),0);val info=MediaCodec.BufferInfo()
while(isActive){bytesRead=input.read(buf);if(bytesRead<=0)break;totalRead+=bytesRead;feed(codec,ByteBuffer.wrap(buf,0,bytesRead),0)
var o=codec.dequeueOutputBuffer(info,5000)
while(o>=0){val b=codec.getOutputBuffer(o)!!;val fc=info.size/2
if(fc>0){b.position(info.offset);val s=ShortArray(fc);b.asShortBuffer().get(s);val f=FloatArray(fc)
for(i in 0 until fc)f[i]=s[i]/32768f
for(l in listeners)try{l(f)}catch(_:Exception){}}
codec.releaseOutputBuffer(o,false);o=codec.dequeueOutputBuffer(info,0)}}
feed(codec,ByteBuffer.allocate(0),MediaCodec.BUFFER_FLAG_END_OF_STREAM);o=codec.dequeueOutputBuffer(info,10000)
while(o>=0){if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0)break;codec.releaseOutputBuffer(o,false);o=codec.dequeueOutputBuffer(info,0)}
codec.stop();codec.release();input.close();conn.disconnect()}catch(e:Exception){if(isActive)Log.e(TAG,"err",e)}}}
private fun feed(c:MediaCodec,d:ByteBuffer,f:Int){var i=c.dequeueInputBuffer(10000)
while(i>=0&&d.hasRemaining()){val b=c.getInputBuffer(i)!!;val n=minOf(b.remaining(),d.remaining())
if(n>0){val s=d.slice();s.limit(n);b.put(s);d.position(d.position()+n)}
c.queueInputBuffer(i,0,n,0,f);if(!d.hasRemaining())break;i=c.dequeueInputBuffer(10000)}}
fun stop(){job?.cancel();job=null}}