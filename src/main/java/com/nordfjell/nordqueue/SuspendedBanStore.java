package com.nordfjell.nordqueue;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** One storage writer, immutable lock-free readers. Publish only after durable replacement. */
final class SuspendedBanStore {
    private static final long MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_RECORDS = 50000;
    @FunctionalInterface interface Persister { void save(Properties properties) throws IOException; }
    private final Path file;
    private final Persister persister;
    private volatile Map<String,SuspendedBan> records = Map.of();
    SuspendedBanStore(Path file) { this(file,null); }
    SuspendedBanStore(Path file,Persister persister) {
        this.file=file.toAbsolutePath().normalize();
        this.persister=persister==null ? this::writeAtomic : persister;
    }
    synchronized void load() throws IOException {
        Files.createDirectories(file.getParent());
        if(!Files.exists(file)) { records=Map.of(); return; }
        if(Files.size(file)>MAX_BYTES) throw new IOException("Ban file size limit exceeded");
        Properties p=new Properties(){
            @Override public synchronized Object put(Object key,Object value) {
                if(containsKey(key)) throw new IllegalArgumentException("Duplicate ban property");
                return super.put(key,value);
            }
        };
        try(InputStream input=Files.newInputStream(file)){p.load(input);}
        catch(IllegalArgumentException e){throw new IOException("Invalid ban properties",e);}
        if(p.size()>MAX_RECORDS) throw new IOException("Ban record limit exceeded");
        Map<String,SuspendedBan> next=new HashMap<>();
        for(String key:p.stringPropertyNames()) {
            SuspendedBan ban=decode(p.getProperty(key));String normalized=normalize(key);
            if(!normalized.equals(normalize(ban.playerName()))) throw new IOException("Ban name/key mismatch");
            if(next.putIfAbsent(normalized,ban)!=null) throw new IOException("Duplicate normalized account");
        }
        long now=System.currentTimeMillis();next.values().removeIf(ban->!ban.active(now));
        records=Map.copyOf(next);
    }
    Optional<SuspendedBan> active(String name) {
        SuspendedBan ban=records.get(normalize(name));
        return ban!=null && ban.active(System.currentTimeMillis()) ? Optional.of(ban) : Optional.empty();
    }
    boolean matches(BanInbox.Change change) {
        SuspendedBan current=records.get(normalize(change.name()));
        return change.ban()==null ? current==null : change.ban().equals(current);
    }
    synchronized void put(SuspendedBan ban) throws IOException { apply(List.of(new BanInbox.Change(ban.playerName(),ban)),false); }
    synchronized Optional<SuspendedBan> remove(String name) throws IOException {
        SuspendedBan old=records.get(normalize(name));apply(List.of(new BanInbox.Change(name,null)),false);return Optional.ofNullable(old);
    }
    synchronized List<SuspendedBan> removeExpired(long now) throws IOException {
        List<SuspendedBan> expired=records.values().stream().filter(ban->!ban.active(now)).toList();
        apply(expired.stream().map(ban->new BanInbox.Change(ban.playerName(),null)).toList(),false);return expired;
    }
    synchronized boolean apply(List<BanInbox.Change> changes,boolean cleanup) throws IOException {
        Map<String,SuspendedBan> next=new HashMap<>(records);
        for(BanInbox.Change change:changes) {
            String name=normalize(change.name());
            if(!validName(change.name())) throw new IOException("Invalid account name");
            if(change.ban()==null) next.remove(name);
            else {validate(change.ban());if(!name.equals(normalize(change.ban().playerName())))throw new IOException("Name mismatch");next.put(name,change.ban());}
        }
        if(cleanup){long now=System.currentTimeMillis();next.values().removeIf(ban->!ban.active(now));}
        if(next.equals(records)) return false;
        if(next.size()>MAX_RECORDS) throw new IOException("Ban record limit exceeded");
        Properties p=new Properties();next.forEach((name,ban)->p.setProperty(name,encode(ban)));
        Map<String,SuspendedBan> committed=Map.copyOf(next);
        persister.save(p);records=committed;return true;
    }
    private void writeAtomic(Properties p) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();p.store(bytes,"NordQueue suspended temporary bans.");
        if(bytes.size()>MAX_BYTES) throw new IOException("Ban file size limit exceeded");
        Path temporary=Files.createTempFile(file.getParent(),file.getFileName()+".",".tmp");
        try {
            try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                ByteBuffer buffer=ByteBuffer.wrap(bytes.toByteArray());while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary);}
    }
    static void validate(SuspendedBan ban) throws IOException {
        if(ban==null || !validName(ban.playerName()) || ban.expiresAtMillis()<=0 || ban.reason()==null
            || ban.reason().isBlank() || ban.reason().length()>256 || ban.reason().chars().anyMatch(Character::isISOControl))
            throw new IOException("Invalid ban fields");
    }
    static boolean validName(String name){return name!=null && name.length()<=32 && name.matches("[A-Za-z0-9_.-]+");}
    static String normalize(String name){return name.toLowerCase(Locale.ROOT);}
    private static String encode(SuspendedBan ban){return ban.expiresAtMillis()+"|"+text(ban.playerName())+"|"+text(ban.reason());}
    private static String text(String value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
    private static String untext(String value) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(value))).toString();
    }
    private static SuspendedBan decode(String value) throws IOException {
        try {
            String[] fields=value.split("\\|",-1);if(fields.length!=3)throw new IllegalArgumentException("Invalid field count");
            SuspendedBan ban=new SuspendedBan(untext(fields[1]),Long.parseLong(fields[0]),untext(fields[2]));validate(ban);return ban;
        }catch(RuntimeException|CharacterCodingException e){throw new IOException("Invalid ban record",e);}
    }
}
