package com.cryptotrader.bybit
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
data class Quote(val t:Long,val seg:Int,val b:DoubleArray,val a:DoubleArray)
fun main(){
 val fmt=DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss.SSS")
 val q=File("forex-data/eurusd-research-2020-2022.csv").useLines { lines -> lines.drop(1).map { l ->
 val x=l.split(","); Quote(LocalDateTime.parse(x[1],fmt).toInstant(ZoneOffset.UTC).toEpochMilli(),x[0].toInt(),DoubleArray(4){x[it+2].toDouble()},DoubleArray(4){x[it+6].toDouble()})
 }.toList() }
 File("forex-signals.csv").bufferedWriter().use { w ->
 w.write("tf,year,time,pattern,side,horizonBars,netPips,status\n")
 for(tf in listOf(1,5,15,30,60)){
 val step=tf*60000L
 val bars=ArrayList<Candle>(); val indices=ArrayList<Int>()
 var j=0
 while(j<q.size){
 val start=j; val t=q[j].t; val bucket=t/step
 while(j<q.size && q[j].t/step==bucket) j++
 if(j-start!=tf || t%step!=0L || (start until j).any{q[it].t!=t+(it-start)*60000L || q[it].seg!=q[start].seg}) continue
 val v=q.subList(start,j)
 bars.add(Candle(t,v.first().b[0],v.maxOf{it.b[1]},v.minOf{it.b[2]},v.last().b[3],0.0)); indices.add(start)
 }
 for(i in 6 until bars.size-1){
 if((i-5..i+1).any{bars[it].time-bars[it-1].time!=step || q[indices[it]].seg!=q[indices[it-1]].seg}) continue
 val k=indices[i+1]; val e=q[k]; val o=e.b[0]
 val s=SignalDetector.detectReversalSignal(bars.subList(i-6,i+1)+Candle(e.t,o,o,o,o,0.0),null,0.0,timeframeMinutes=tf)
 if(s.type !in listOf("long","short")) continue
 val long=s.type=="long"; val entry=if(long)e.a[0] else e.b[0]
 for(h in listOf(1,3,5,10)){
 val end=k+h*tf-1
 val ok=end<q.size && q[end].seg==e.seg && q[end].t-e.t==(end-k)*60000L
 val ret=if(!ok) "" else (((if(long)q[end].b[3]-entry else entry-q[end].a[3])*10000)).toString()
 w.write(listOf(tf,java.time.Instant.ofEpochMilli(e.t).atOffset(ZoneOffset.UTC).year,e.t,s.pattern.substringBefore(" · "),s.type,h,ret,if(ok)"ok" else "gap").joinToString(",")+"\n")
 }
 }
 }
 }
}
