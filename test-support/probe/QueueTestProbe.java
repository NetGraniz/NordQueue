package com.nordfjell.nordqueuetest;

import com.google.inject.Inject;
import com.google.gson.Gson;
import com.nordfjell.nordqueue.NordQueuePlugin;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;
import org.slf4j.Logger;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

/** LOCAL TEST ONLY: controlled connection failures, delayed events and state inspection. */
public final class QueueTestProbe {
    private final ProxyServer proxy;
    private final Logger logger;
    private NordQueuePlugin queue;
    private volatile String blocked = "";
    private volatile String held = "";
    private final ConcurrentLinkedQueue<Continuation> continuations = new ConcurrentLinkedQueue<>();
    private final Gson gson = new Gson();
    private volatile boolean storageFault;
    private volatile long storageSlow;

    @Inject public QueueTestProbe(ProxyServer proxy, Logger logger) { this.proxy = proxy; this.logger = logger; }

    @Subscribe public void initialize(ProxyInitializeEvent event) {
        queue = (NordQueuePlugin) proxy.getPluginManager().getPlugin("nordqueue").orElseThrow()
            .getInstance().orElseThrow();
        try {
            var storeField=queue.getClass().getDeclaredField("suspendedBanStore");storeField.setAccessible(true);
            Object store=storeField.get(queue);var persisterField=store.getClass().getDeclaredField("persister");persisterField.setAccessible(true);
            Object original=persisterField.get(store);Class<?> type=persisterField.getType();
            Object controlled=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(ignored,method,args)->{
                if(storageSlow>0)Thread.sleep(storageSlow);
                if(storageFault)throw new IOException("LOCAL_TEST_INJECTED_STORAGE_FAILURE");
                method.setAccessible(true);
                try{return method.invoke(original,args);}catch(InvocationTargetException error){throw error.getCause();}
            });
            persisterField.set(store,controlled);
        }catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("qtest").plugin(this).build(),
            (SimpleCommand) invocation -> {
                if (invocation.source() instanceof Player) return;
                String[] args = invocation.arguments();
                if (args.length < 1) return;
                switch (args[0]) {
                    case "storagefault" -> storageFault=Boolean.parseBoolean(args[1]);
                    case "storageslow" -> storageSlow=Long.parseLong(args[1]);
                    case "storagestate" -> {
                        try{
                            var inboxField=queue.getClass().getDeclaredField("banInbox");inboxField.setAccessible(true);Object inbox=inboxField.get(queue);
                            var storeField=queue.getClass().getDeclaredField("suspendedBanStore");storeField.setAccessible(true);Object store=storeField.get(queue);
                            var recordsField=store.getClass().getDeclaredField("records");recordsField.setAccessible(true);
                            Map<String,Object> data=new HashMap<>();List<String> active=new ArrayList<>();
                            for(Object ban:((Map<?,?>)recordsField.get(store)).values()){
                                var valid=ban.getClass().getDeclaredMethod("active",long.class);valid.setAccessible(true);
                                if((boolean)valid.invoke(ban,System.currentTimeMillis())){
                                    var name=ban.getClass().getDeclaredMethod("playerName");name.setAccessible(true);active.add((String)name.invoke(ban));
                                }
                            }
                            data.put("active",active.stream().sorted().toList());data.put("writers",Thread.getAllStackTraces().keySet().stream().filter(t->t.getName().equals("NordQueue-ban-storage")).count());
                            for(String name:List.of("ready","pendingCount","problem")){
                                var method=inbox.getClass().getDeclaredMethod(name);method.setAccessible(true);data.put(name,method.invoke(inbox));
                            }
                            logger.info("STORESTATE {} {}",args[1],gson.toJson(data));
                        }catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
                    }
                    case "block" -> blocked = args.length > 1 ? args[1] : "";
                    case "hold" -> held = args.length > 1 ? args[1] : "";
                    case "ban", "unban" -> {
                        try {
                            if (args[0].equals("ban")) {
                                Class<?> type = Class.forName("com.nordfjell.nordqueue.SuspendedBan", true,
                                    queue.getClass().getClassLoader());
                                var ctor = type.getDeclaredConstructor(String.class, long.class, String.class);
                                ctor.setAccessible(true);
                                var method = queue.getClass().getDeclaredMethod("applyBan", type);
                                method.setAccessible(true);
                                method.invoke(queue, ctor.newInstance(args[1], System.currentTimeMillis() + 60000, "Local fixture ban"));
                            } else {
                                var method = queue.getClass().getDeclaredMethod("removeBan", String.class);
                                method.setAccessible(true);
                                method.invoke(queue, args[1]);
                            }
                        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                    }
                    case "resume" -> {
                        held = "";
                        Continuation continuation;
                        while ((continuation = continuations.poll()) != null) continuation.resume();
                    }
                    case "state" -> {
                        var snapshot = queue.snapshot();
                        var main = proxy.getServer("main").orElseThrow().getPlayersConnected().stream()
                            .map(Player::getUsername).sorted().toList();
                        var limbo = proxy.getServer("queue").orElseThrow().getPlayersConnected().stream()
                            .map(Player::getUsername).sorted().toList();
                        List<String> waiting = snapshot.orderedIds().stream()
                            .map(id -> proxy.getPlayer(id).map(Player::getUsername).orElse("offline")).toList();
                        logger.info("QSTATE {} {}", args[1], gson.toJson(Map.of("main", main, "limbo", limbo,
                            "waiting", waiting, "regular", snapshot.regularSize(), "priority", snapshot.prioritySize(),
                            "held", continuations.size(), "version", snapshot.version())));
                    }
                    default -> { }
                }
            });
        logger.info("LOCAL_QUEUE_PROBE_READY");
    }

    @Subscribe(order = PostOrder.FIRST)
    public EventTask preConnect(ServerPreConnectEvent event) {
        if (!event.getOriginalServer().getServerInfo().getName().equals("main")) return null;
        if (event.getPlayer().getUsername().equals(blocked)) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return null;
        }
        if (event.getPlayer().getUsername().equals(held)) {
            return EventTask.withContinuation(continuations::add);
        }
        return null;
    }
}
