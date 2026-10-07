package com.apple.android.music.player.viewmodel;
/** Real 1606 getter names with deterministic observer behavior for hook tests. */
public class PlayerLyricsViewModel {
 public static class ReadOnlyLive {
  protected Object value;
  public int events;
  public java.util.function.Consumer<Boolean> observer;
  public ReadOnlyLive(boolean value) {this.value=value;}
  public Object getValue() {return value;}
  protected void setValue(Object value) { this.value=value;events++;if(observer!=null)observer.accept((Boolean)value); }
 }
 public static class Live extends ReadOnlyLive {
  public Live(boolean value) {super(value);}
  @Override public void setValue(Object value) {super.setValue(value);}
 }
 public ReadOnlyLive pronunciation=new ReadOnlyLive(true), translation=new ReadOnlyLive(true);
 public Live pronunciationAvailable=new Live(false), translationAvailable=new Live(false);
 public ReadOnlyLive getPronunciationSelectedLiveResult() {return pronunciation;}
 public ReadOnlyLive getTranslationSelectedLiveResult() {return translation;}
 public Live getPronunciationAvailableLiveResult() {return pronunciationAvailable;}
 public Live getTranslationAvailableLiveResult() {return translationAvailable;}
 public void choose(boolean pronunciation,boolean value) { (pronunciation?this.pronunciation:this.translation).setValue(value); }
}
