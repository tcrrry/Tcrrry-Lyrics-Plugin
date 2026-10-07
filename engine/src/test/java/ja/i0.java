package ja;
import com.apple.android.music.player.viewmodel.PlayerLyricsViewModel;
/** Native menu uses these exact static setters. Test captures persistent selection API calls. */
public class i0 {
 public static PlayerLyricsViewModel model;
 public static int pronunciationWrites, translationWrites;
 public static void m(boolean selected) {pronunciationWrites++;model.choose(true,selected);}
 public static void n(boolean selected) {translationWrites++;model.choose(false,selected);}
}
