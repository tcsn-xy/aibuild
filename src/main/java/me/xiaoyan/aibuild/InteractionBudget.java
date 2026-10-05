package me.xiaoyan.aibuild;
/** Render-thread load signal; server-thread reads only. Never touches input or camera. */
public final class InteractionBudget {
 private static volatile boolean playing;
 private static volatile long slowUntil;
 private static long lastFrame;
 private InteractionBudget(){}
 public static void frame(boolean active,long now){
  if(active&&playing&&lastFrame!=0&&now-lastFrame>50_000_000L)slowUntil=now+1_000_000_000L;
  if(!active)slowUntil=0;
  playing=active;lastFrame=now;
 }
 public static void remotePlaying(boolean active){playing=active;slowUntil=0;}
 public static int steps(long now){return !playing?512:now<slowUntil?32:128;}
 public static long nanos(){return playing?500_000L:2_000_000L;}
}
