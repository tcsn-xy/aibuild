package me.xiaoyan.aibuild.qa;
import me.xiaoyan.aibuild.InteractionBudget;
public final class InputBudgetQA {
 private static int checks;
 private static void check(boolean v,String n){if(!v)throw new AssertionError(n);checks++;}
 public static void main(String[] args){
  long t=10_000_000_000L;
  InteractionBudget.frame(false,t);check(InteractionBudget.steps(t)==512,"background still progresses");
  InteractionBudget.frame(true,t+10_000_000);check(InteractionBudget.steps(t+10_000_000)==128,"foreground rate reduced");check(InteractionBudget.nanos()<=500_000,"foreground server time budget");
  InteractionBudget.frame(true,t+100_000_000);check(InteractionBudget.steps(t+100_000_000)==32,"long frame slows construction");
  InteractionBudget.frame(true,t+110_000_000);check(InteractionBudget.steps(t+110_000_000)==32,"one good frame does not oscillate rate");
  check(InteractionBudget.steps(t+1_200_000_000)==128,"load recovery restores normal foreground rate");
  InteractionBudget.frame(false,t+1_210_000_000);check(InteractionBudget.steps(t+1_210_000_000)==512,"background resumes original ceiling");check(InteractionBudget.nanos()==2_000_000,"background preserves original time budget");
  InteractionBudget.frame(true,t+20_000_000_000L);check(InteractionBudget.steps(t+20_000_000_000L)==128,"refocus ignores time spent outside game");
  System.out.println("INPUT_BUDGET_QA_PASS "+checks);
 }
}
