package com.tcrrry.lyrics.plugin
import java.util.concurrent.*
object PluginExecutors {
    private fun <T:ExecutorService> track(service:T):T { PluginLifetime.close { service.shutdownNow() };return service }
    fun pool(core:Int,max:Int,keepAlive:Long,unit:TimeUnit,queue:BlockingQueue<Runnable>,factory:ThreadFactory,policy:RejectedExecutionHandler):ThreadPoolExecutor =
        track(ThreadPoolExecutor(core,max,keepAlive,unit,queue,factory,policy))
    fun pool(core:Int,max:Int,keepAlive:Long,unit:TimeUnit,queue:BlockingQueue<Runnable>):ThreadPoolExecutor =
        track(ThreadPoolExecutor(core,max,keepAlive,unit,queue))
    fun pool(core:Int,max:Int,keepAlive:Long,unit:TimeUnit,queue:BlockingQueue<Runnable>,factory:ThreadFactory):ThreadPoolExecutor =
        track(ThreadPoolExecutor(core,max,keepAlive,unit,queue,factory))
    fun newSingleThreadExecutor(factory:ThreadFactory):ExecutorService=track(Executors.newSingleThreadExecutor(factory))
    fun newSingleThreadExecutor():ExecutorService=track(Executors.newSingleThreadExecutor())
    fun newFixedThreadPool(count:Int,factory:ThreadFactory):ExecutorService=track(Executors.newFixedThreadPool(count,factory))
    fun newSingleThreadScheduledExecutor(factory:ThreadFactory):ScheduledExecutorService=track(Executors.newSingleThreadScheduledExecutor(factory))
    fun newSingleThreadScheduledExecutor():ScheduledExecutorService=track(Executors.newSingleThreadScheduledExecutor())
    fun newCachedThreadPool(factory:ThreadFactory):ExecutorService=track(Executors.newCachedThreadPool(factory))
    fun newFixedThreadPool(count:Int):ExecutorService=track(Executors.newFixedThreadPool(count))
}
