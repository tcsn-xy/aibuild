package me.xiaoyan.aibuild;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class FilesIO {
 public static final Gson GSON=new GsonBuilder().disableHtmlEscaping().create();
 public static void atomic(Path path,Object obj)throws Exception{
  Files.createDirectories(path.getParent());Path temp=path.resolveSibling(path.getFileName()+"."+UUID.randomUUID()+".tmp");
  byte[] bytes=GSON.toJson(obj).getBytes(StandardCharsets.UTF_8);
  try{try(var channel=FileChannel.open(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
 }
 public static JsonObject read(Path path)throws Exception{if(Files.size(path)>48*1024*1024)throw new IllegalArgumentException("文件超过48MiB");return JsonParser.parseString(Files.readString(path)).getAsJsonObject();}
 public static String id(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("非法请求或任务ID");return value;}
}
