package com.nordfjell.nordqueue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Read-only cross-process snapshot of the NordBans SQLite database. */
final class BanDatabaseReader implements AutoCloseable {
    private final Path database;
    private volatile Map<String,SuspendedBan> bans=Map.of();
    private volatile String problem="not initialized";
    private volatile boolean closed;
    BanDatabaseReader(Path database){this.database=database.toAbsolutePath().normalize();}
    void refresh(){
        if(closed)return;
        if(!Files.isRegularFile(database)){problem="database file is missing";return;}
        String url="jdbc:sqlite:"+database.toUri()+"?mode=ro";
        try(Connection c=DriverManager.getConnection(url);Statement setup=c.createStatement()){
            setup.execute("PRAGMA busy_timeout=5000");Map<String,SuspendedBan> next=new HashMap<>();long now=System.currentTimeMillis();
            try(Statement s=c.createStatement();ResultSet rows=s.executeQuery("SELECT normalized_name,player_name,expires_at,reason FROM bans WHERE expires_at>"+now)){
                while(rows.next()){
                    String key=rows.getString(1),name=rows.getString(2);long expires=rows.getLong(3);String reason=rows.getString(4);
                    if(key==null||name==null||reason==null||!key.equals(name.toLowerCase(Locale.ROOT))||expires<=now)throw new SQLException("Invalid NordBans row");
                    if(next.putIfAbsent(key,new SuspendedBan(name,expires,reason))!=null)throw new SQLException("Duplicate NordBans identity");
                    if(next.size()>50_000)throw new SQLException("NordBans row limit exceeded");
                }
            }
            bans=Map.copyOf(next);problem="";
        }catch(SQLException|RuntimeException e){problem="read failed ("+e.getClass().getSimpleName()+")";}
    }
    Optional<SuspendedBan> active(String name){SuspendedBan ban=bans.get(name.toLowerCase(Locale.ROOT));return ban!=null&&ban.active(System.currentTimeMillis())?Optional.of(ban):Optional.empty();}
    boolean ready(){return !closed&&problem.isEmpty();}
    String problem(){return problem;}
    @Override public void close(){closed=true;bans=Map.of();problem="closed";}
}
