package com.nordfjell.nordqueue;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

final class PingFeature {
    private static final String DEFAULT_VERSION_NAME = "Minecraft 26.3+";
    private final NordQueuePlugin owner;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path config;
    private final AtomicBoolean reloadPending = new AtomicBoolean();
    private volatile String versionName = DEFAULT_VERSION_NAME;
    private volatile boolean stopping;

    PingFeature(NordQueuePlugin owner, ProxyServer proxy, Logger logger, Path config) {
        this.owner=owner; this.proxy=proxy; this.logger=logger; this.config=config;
    }

    void start() {
        try { versionName=load(true); }
        catch (IOException|RuntimeException error) { logger.error("Ping configuration rejected; using {}",DEFAULT_VERSION_NAME,error); }
        proxy.getEventManager().register(owner,this);
        logger.info("Integrated ping label enabled: {}",versionName);
    }

    void stop(){stopping=true;}
    String health(){return "ping="+(stopping?"stopped":"ready")+", version="+versionName;}
    boolean reload(com.velocitypowered.api.command.CommandSource source) {
        if(stopping||!reloadPending.compareAndSet(false,true)) return false;
        proxy.getScheduler().buildTask(owner,()->{try{versionName=load(false);source.sendPlainMessage("NordQueue ping configuration reloaded: "+versionName);}catch(Exception error){logger.error("Ping reload rejected; previous label retained",error);source.sendPlainMessage("Ping reload rejected; previous label retained.");}finally{reloadPending.set(false);}}).schedule();
        return true;
    }

    @Subscribe(priority=Short.MIN_VALUE)
    public void ping(ProxyPingEvent event){
        ServerPing current=event.getPing();
        event.setPing(current.asBuilder().version(new ServerPing.Version(current.getVersion().getProtocol(),versionName)).build());
    }

    private String load(boolean create) throws IOException {
        Files.createDirectories(config.getParent());Properties properties=new Properties();
        if(Files.exists(config))try(InputStream input=Files.newInputStream(config)){properties.load(input);}
        if(!properties.stringPropertyNames().stream().allMatch(Set.of("version-name")::contains))throw new IOException("Unknown ping setting");
        String value=properties.getProperty("version-name",DEFAULT_VERSION_NAME).trim();
        if(value.isEmpty()||value.length()>128||value.chars().anyMatch(Character::isISOControl))throw new IOException("Invalid version-name");
        if(create&&!Files.exists(config)){properties.setProperty("version-name",value);try(OutputStream output=Files.newOutputStream(config)){properties.store(output,"NordQueue server-list version label");}}
        return value;
    }
}
