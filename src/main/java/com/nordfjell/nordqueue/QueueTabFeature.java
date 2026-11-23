package com.nordfjell.nordqueue;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

final class QueueTabFeature {
    private final NordQueuePlugin owner;private final ProxyServer proxy;private final Logger logger;private final Path config;
    private final Object lock=new Object();private final QueueTabRenderer renderer=new QueueTabRenderer();
    private QueueTabSettings settings=QueueTabSettings.defaults();private ScheduledTask task;private long generation,failedAt;private boolean stopped;
    QueueTabFeature(NordQueuePlugin owner,ProxyServer proxy,Logger logger,Path config){this.owner=owner;this.proxy=proxy;this.logger=logger;this.config=config;}
    void start(){synchronized(lock){reloadSettings();startTask();}}
    private void startTask(){if(task!=null)task.cancel();long expected=++generation;task=proxy.getScheduler().buildTask(owner,()->refresh(expected)).delay(1,TimeUnit.SECONDS).repeat(settings.intervalMillis(),TimeUnit.MILLISECONDS).schedule();}
    private void refresh(long expected){synchronized(lock){if(stopped||expected!=generation)return;try{renderer.refresh(proxy,owner.snapshot(),owner.queueServerName(),settings);}catch(RuntimeException e){long now=System.nanoTime();if(failedAt==0||now-failedAt>TimeUnit.SECONDS.toNanos(30)){failedAt=now;logger.error("Queue TAB refresh failed; next refresh will retry",e);}}}}
    void connected(Player player,boolean inQueue){synchronized(lock){if(!stopped)renderer.connected(player,inQueue);}}
    void disconnected(Player player){synchronized(lock){renderer.forget(player);}}
    boolean reload(){synchronized(lock){boolean ok=!stopped&&reloadSettings();if(ok)startTask();return ok;}}
    String health(){synchronized(lock){return "tab="+(!stopped?"ready":"stopped")+", viewers="+renderer.viewerCount()+", entries="+renderer.ownedCount();}}
    private boolean reloadSettings(){try{Files.createDirectories(config.getParent());Properties p=new Properties();if(Files.exists(config))try(InputStream in=Files.newInputStream(config)){p.load(in);}QueueTabSettings next=QueueTabSettings.from(p);next.validate(MiniMessage.miniMessage());if(!Files.exists(config)){next.writeDefaults(p);try(OutputStream out=Files.newOutputStream(config)){p.store(out,"NordQueue TAB settings");}}settings=next;return true;}catch(Exception e){logger.error("Could not load queue TAB configuration; keeping previous settings",e);return false;}}
    void stop(){synchronized(lock){stopped=true;generation++;if(task!=null)task.cancel();renderer.forgetAll();}}
}
