package com.nordfjell.nordqueue;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class QueueNoticeFeature {
    private final NordQueuePlugin owner;private final ProxyServer proxy;private final Logger logger;private final Path config;
    private final Object lifecycle=new Object();private final NoticeSchedule<Player> schedule=new NoticeSchedule<>();
    private final DispatchBudget dispatchBudget=new DispatchBudget();private final AtomicBoolean ticking=new AtomicBoolean();
    private NoticeSettings settings;private ReloadReader<NoticeSettings> reader;private ScheduledTask task;private CommandSource requester;
    private Callable<NoticeSettings> loader;private boolean stopping;private long dropped;
    QueueNoticeFeature(NordQueuePlugin owner,ProxyServer proxy,Logger logger,Path config){this.owner=owner;this.proxy=proxy;this.logger=logger;this.config=config;loader=()->NoticeSettings.load(config,false);}
    void start(){synchronized(lifecycle){reader=new ReloadReader<>();try{settings=NoticeSettings.load(config,true);}catch(Exception e){logger.error("Queue notice config rejected; notices disabled until valid reload: {}",e.getClass().getSimpleName());}task=proxy.getScheduler().buildTask(owner,this::tick).delay(NoticeSchedule.TICK_MILLIS,TimeUnit.MILLISECONDS).repeat(NoticeSchedule.TICK_MILLIS,TimeUnit.MILLISECONDS).schedule();}}
    private boolean current(Player p){return p.isActive()&&proxy.getPlayer(p.getUniqueId()).orElse(null)==p;}
    private Optional<ServerConnection> connection(Player p){return p.getCurrentServer().filter(c->c.getServerInfo().getName().equalsIgnoreCase(owner.queueServerName()));}
    void connected(Player p){synchronized(lifecycle){if(stopping)return;Optional<ServerConnection> c=connection(p);if(c.isPresent()){long delay=settings==null?TimeUnit.MILLISECONDS.toNanos(750):settings.initialDelayNanos();if(!schedule.track(p.getUniqueId(),p,c.get(),System.nanoTime(),delay))dropped++;}else schedule.remove(p.getUniqueId(),p);}}
    void disconnected(Player p){synchronized(lifecycle){schedule.remove(p.getUniqueId(),p);}}
    private void tick(){if(!ticking.compareAndSet(false,true))return;try{synchronized(lifecycle){if(stopping)return;reader.drain(this::publish);NoticeSettings active=settings;if(active==null)return;int budget=Math.min(NoticeSchedule.SCAN_BUDGET,schedule.size()),sent=0;for(int inspected=0;inspected<budget&&sent<NoticeSchedule.SEND_BUDGET;inspected++){if(!dispatchBudget.available(System.nanoTime()))break;var entry=schedule.next();if(entry==null)break;Player p=entry.owner;Optional<ServerConnection> c=p.getCurrentServer();if(!current(p)||c.orElse(null)!=entry.connection||!c.get().getServerInfo().getName().equalsIgnoreCase(owner.queueServerName())){schedule.remove(entry.id,p);continue;}if(!owner.isQueued(entry.id))continue;long now=System.nanoTime();if(!schedule.due(entry,now)||!dispatchBudget.take(now)||!schedule.claim(entry,now,active.intervalNanos()))continue;sent++;if(current(p)&&p.getCurrentServer().orElse(null)==entry.connection&&owner.isQueued(entry.id))p.sendMessage(active.frame());}}}catch(RuntimeException e){logger.warn("Queue notice work failed: {}",e.getClass().getSimpleName());}finally{ticking.set(false);}}
    boolean reload(CommandSource source){synchronized(lifecycle){if(stopping||reader==null||!reader.start(loader))return false;requester=source;return true;}}
    String health(){synchronized(lifecycle){return "notices="+(settings!=null&&!stopping?"ready":"blocked")+", tracked="+schedule.size()+", dropped="+dropped;}}
    private void publish(ReloadReader.Outcome<NoticeSettings> outcome){CommandSource source=requester;requester=null;String message;if(outcome.value()!=null){settings=outcome.value();schedule.reset(System.nanoTime(),settings.initialDelayNanos());message="NordQueue notice configuration reloaded.";}else message="NordQueue notice reload rejected; previous configuration retained.";if(source!=null)source.sendMessage(Component.text(message));}
    void stop(){ReloadReader<NoticeSettings> close;synchronized(lifecycle){if(stopping)return;stopping=true;if(task!=null)task.cancel();schedule.close();requester=null;close=reader;}if(close!=null)close.close();}
}
