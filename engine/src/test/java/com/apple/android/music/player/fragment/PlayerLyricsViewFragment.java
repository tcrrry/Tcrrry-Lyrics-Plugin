package com.apple.android.music.player.fragment;
import com.apple.android.music.ttml.javanative.model.SongInfo;
import com.apple.android.music.player.viewmodel.PlayerLyricsViewModel;
public class PlayerLyricsViewFragment {
 public SongInfo.SongInfoPtr h1=new SongInfo.SongInfoPtr();
 public PlayerLyricsViewModel o1=new PlayerLyricsViewModel();
 public boolean t2(SongInfo.SongInfoPtr pointer) {return false;}
 public boolean u2(SongInfo.SongInfoPtr pointer) {return false;}
 public void F2(SongInfo.SongInfoPtr pointer) { h1=pointer;o1.pronunciationAvailable.setValue(false);o1.translationAvailable.setValue(false); }
}
