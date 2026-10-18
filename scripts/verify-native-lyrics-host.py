"""Verify exact 1606/1609 native lyric contracts against an original host APK."""
import argparse
import importlib.util
from pathlib import Path
import re
import subprocess
import zipfile

import json
import host_dex as host


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('apk', type=Path)
    p.add_argument('--aapt2', type=Path, required=True)
    args = p.parse_args()
    badging = subprocess.check_output([str(args.aapt2), 'dump', 'badging', str(args.apk)], text=True)
    version = re.search(r"versionCode='(1606|1609)' versionName='7.0.0-beta'", badging)
    if "name='com.apple.android.music'" not in badging or not version:
        p.error('Requires original Apple Music 7.0.0-beta / 1606 or 1609')
    classes = {}
    with zipfile.ZipFile(args.apk) as apk:
        for name in apk.namelist():
            if re.fullmatch(r'classes\d*\.dex', name):
                classes.update(host.dex_classes(apk.read(name)))
    profile = json.loads((Path(__file__).resolve().parents[1]/'assets/host-profiles'/f'7.0.0-beta-{version[1]}.json').read_text(encoding='utf8'))
    contract = profile.get('pluginLyricsCompatibility', {})
    renames = {k.replace('.', '/'):v.replace('.', '/') for k,v in contract.get('classes', {}).items()}
    def owner_name(name): return renames.get(name, name)
    def descriptor_name(value):
        return re.sub(r'L([A-Za-z0-9_$]+/[^;]+);', lambda m:'L'+owner_name(m[1])+';', value)
    def mapped_field_name(owner, name):
        return contract.get('fields', {}).get(owner.replace('/', '.'), {}).get(name, name)
    checks = 0
    def method(owner, descriptor, static=False):
        nonlocal checks
        checks += 1
        owner, descriptor = owner_name(owner), descriptor_name(descriptor)
        c = classes['L'+owner+';']
        if descriptor not in c['methods'] or bool(c['method_access'][descriptor] & 8) != static:
            raise RuntimeError(f'Wrong method contract: {owner}.{descriptor}')
    def field(owner, name, descriptor, static=False):
        nonlocal checks
        checks += 1
        owner, descriptor = owner_name(owner), descriptor_name(descriptor)
        name = mapped_field_name(owner, name)
        c = classes['L'+owner+';']
        if c['fields'].get(name) != descriptor or bool(c['field_access'][name] & 8) != static:
            raise RuntimeError(f'Wrong field contract: {owner}.{name}')
    result = profile.get('lyricResultSubmission')
    if result:
        assert result['contractVersion'] == 1
        required_methods = {'resultSet','resultPost','resultValue','fragmentResume','buildIndex','loadLyrics',
                            'systemLyricsLanguage','pointerGet','pointerAdamId','pointerScript','clear'}
        assert required_methods <= result['methods'].keys()
        assert {'fragmentViewModel','lyricsResult'} <= result['fields'].keys()
        def result_type(name):
            return {'void':'V','boolean':'Z','int':'I','float':'F','long':'J'}.get(name,
                name if name.startswith('[') else 'L'+name.replace('.', '/')+';')
        for entry in result['methods'].values():
            signature = entry['name']+'('+''.join(result_type(t) for t in entry['parameters'])+')'+result_type(entry['returns'])
            method(entry['owner'].replace('.', '/'), signature, entry['static'])
        for entry in result['fields'].values():
            field(entry['owner'].replace('.', '/'), entry['name'], result_type(entry['type']))
    # Plugin 1.0.4/1.0.5 recovery and geometry seams, beyond the original extension.
    field('q8/p9', 'a0', 'Lcom/apple/android/music/common/views/FullWidthAlphaGradientFlexboxLayout;')
    field('q8/p9', 'Z', 'Lcom/apple/android/music/common/views/FullWidthAlphaGradientFlexboxLayout;')
    for name in ('V', 'W'):
        field('com/apple/android/music/common/views/FullWidthAlphaGradientFlexboxLayout', name, 'Landroid/util/SparseArray;')
    field('q8/Y4', 'e0', 'Landroid/widget/FrameLayout;')
    field('com/apple/android/music/player/fragment/PlayerLyricsViewFragment', 'E0', 'Lcom/apple/android/music/player/fragment/PlayerLyricsViewFragment$x;')
    field('com/apple/android/music/player/fragment/PlayerLyricsViewFragment', 'G0', 'Landroidx/databinding/j;')
    pointer_type = 'Lcom/apple/android/music/ttml/javanative/model/SongInfo$SongInfoPtr;'
    for name, signature in [('B2', '('+pointer_type+'Z)V'), ('F2', '('+pointer_type+')V'), ('o2', '()V')]:
        method('com/apple/android/music/player/fragment/PlayerLyricsViewFragment', name+signature)
    for name in ('m', 'n'): method('ja/i0', name+'(Z)V', True)
    field('z3/w', 'J', 'Landroid/os/Bundle;')
    # Word timing enum and independently adjustable line anticipation.
    processor = 'com/apple/android/music/ttml/javanative/SongInfoTimeProcessorJavaCpp$SongInfoTimeProcessorNative'
    method(processor, 'suggestLineOffset(I)V')
    method(processor, 'getSuggestedLineOffset()I')
    method('com/apple/android/music/ttml/javanative/model/SongInfo$SongInfoNative', 'getTiming()J')
    for name in ('None', 'Line', 'Word'):
        field('Ek/a', name, 'LEk/a;', static=True)
    method('com/apple/android/music/ttml/f', 'a()Lcom/apple/android/music/ttml/SongInfoTimeProcessor;')
    method('com/apple/android/music/player/fragment/PlayerLyricsViewFragment', 'd2(Lcom/apple/android/music/player/fragment/PlayerLyricsViewFragment;I)V', static=True)
    # Immediate reanchoring reuses the native current-ID scroll callback after layout.
    lyrics = 'com/apple/android/music/player/fragment/PlayerLyricsViewFragment'
    method(lyrics, 'onResume()V')
    if version[1] == '1609':
        field('q8/X4', 'o0', 'Landroid/widget/ImageView;')
        checks += 1
        constructors = classes['Lq8/X4;']['methods']
        if not any(name.startswith('<init>(') for name in constructors):
            raise RuntimeError('Missing 1609 lyrics binding constructor for early menu anchor')
    field(lyrics, 'n0', 'Lq8/Y4;')
    field('q8/Y4', 'f0', 'Landroidx/recyclerview/widget/RecyclerView;')
    # Header refresh must not depend on native lyrics availability.
    method('q8/Y4', 'q0(Lcom/apple/android/music/model/PlaybackItem;)V')
    method('q8/Y4', 'p0(Lcom/apple/android/music/model/CollectionItemView;)V')
    method('androidx/databinding/ViewDataBinding', 'n()V')
    # Replay the page's own listener to recover its item, duration and lyric document together.
    field('com/apple/android/music/player/fragment/e', 'g0', 'Lcom/apple/android/music/player/fragment/e$d;')
    method('com/apple/android/music/player/fragment/e$d', 'onMediaMetadataChanged(Lz3/w;)V')
    method('androidx/recyclerview/widget/RecyclerView', 'getScrollState()I')
    field(lyrics, 'h1', 'Lcom/apple/android/music/ttml/javanative/model/SongInfo$SongInfoPtr;')
    field(lyrics, 'p0', 'Lcom/apple/android/music/player/n1;')
    field(lyrics, 'V0', 'Z')
    field(lyrics, 'Z0', 'Lcom/apple/android/music/player/fragment/L;')
    method('com/apple/android/music/player/n1', 'w()Ljava/util/TreeSet;')
    method('com/apple/android/music/player/fragment/L', 'b(II)V')
    method('com/apple/android/music/player/n1', 'N()V')
    method('com/apple/android/music/player/n1', 'P(Z)V')
    # Independent replay recovery must include inherited public interface methods,
    # not just declarations on the fragment and its superclass chain.
    def inherited_public(owner, descriptor):
        nonlocal checks
        checks += 1
        owner, descriptor = owner_name(owner), descriptor_name(descriptor)
        pending, seen = ['L'+owner+';'], set()
        while pending:
            current = pending.pop()
            if current in seen or current not in classes:
                continue
            seen.add(current)
            contract = classes[current]
            if descriptor in contract['methods'] and contract['method_access'][descriptor] & 1:
                return current
            pending.extend(contract['interfaces'])
            pending.append(contract['super'])
        raise RuntimeError(f'Missing inherited public method: {owner}.{descriptor}')
    inherited_public('J4/w', 'i0()Lz3/w;')
    getter = 'getMediaBrowser()LJ4/w;'
    if inherited_public(lyrics, getter) != 'Lia/a$c;':
        raise RuntimeError('Inspect changed native playback interface getter')
    if classes['Lia/a$c;']['method_access'][getter] & 0x400:
        raise RuntimeError('Playback interface getter is no longer a default method')
    for descriptor in ('getCurrentPosition()J', 'isPlaying()Z', 'getPlaybackState()I'):
        inherited_public('J4/w', descriptor)
    field('com/apple/android/music/player/fragment/l', 'X', 'Landroid/os/Handler;')
    method(lyrics, 'y2(I)J')
    method('androidx/recyclerview/widget/RecyclerView$f', 'g()V')
    field(lyrics, 'b1', 'Lcom/apple/android/music/ttml/f;')
    names = ('f', 'g', 'n', 'p', 'r')
    for field_name, callback in zip(('c1', 'd1', 'e1', 'f1', 'g1'), names):
        field(lyrics, field_name, 'L'+lyrics+'$'+callback+';')
    method('com/apple/android/music/ttml/f', 'c(Lcom/apple/android/music/ttml/javanative/model/SongInfo$SongInfoPtr;J'+
           ''.join('L'+lyrics+'$'+name+';' for name in names)+')J')
    method('com/apple/android/music/ttml/f', 'd(Lcom/apple/android/music/ttml/javanative/model/SongInfo$SongInfoPtr;'+
           ''.join('L'+lyrics+'$'+name+';' for name in names)+')J')
    # Idle return redirects only the explicit native u1 call to RecyclerView's own scroller.
    field(lyrics, 's0', 'L'+lyrics+'$w;')
    field(lyrics, 't0', 'L'+lyrics+'$u;')
    method('androidx/recyclerview/widget/LinearLayoutManager', 'u1(II)V')
    method('androidx/recyclerview/widget/RecyclerView', 'u0(I)V')
    method('androidx/recyclerview/widget/t', 'i(Landroid/view/View;I)I')
    method('androidx/recyclerview/widget/t', 'e(Landroid/view/View;Landroidx/recyclerview/widget/RecyclerView$z;Landroidx/recyclerview/widget/RecyclerView$y$a;)V')
    field('androidx/recyclerview/widget/RecyclerView$y', 'a', 'I')
    field('androidx/recyclerview/widget/RecyclerView$y', 'b', 'Landroidx/recyclerview/widget/RecyclerView;')
    method('androidx/recyclerview/widget/RecyclerView$y$a', 'b(IIILandroid/view/animation/BaseInterpolator;)V')
    field('androidx/recyclerview/widget/RecyclerView$k', 'e', 'J')
    field('lc/A', 'w', 'Landroid/view/animation/PathInterpolator;', True)
    # Visibility/emphasis interlock listens to the same selected-state observer as Apple.
    method('com/apple/android/music/player/fragment/PlayerLyricsViewFragment$37', 'onChanged(Ljava/lang/Boolean;)V')
    method('com/apple/android/music/player/viewmodel/PlayerLyricsViewModel', 'getPronunciationSelectedLiveResult()Landroidx/lifecycle/G;')
    method('androidx/lifecycle/G', 'getValue()Ljava/lang/Object;')
    field('com/apple/android/music/player/fragment/PlayerLyricsViewFragment', 'o1', 'Lcom/apple/android/music/player/viewmodel/PlayerLyricsViewModel;')
    base = 'com/apple/android/music/player/n1'
    adapter = 'com/apple/android/music/player/A'
    flex = 'Lcom/apple/android/music/common/views/FullWidthAlphaGradientFlexboxLayout;'
    holder = 'Landroidx/recyclerview/widget/RecyclerView$D;'
    field(adapter+'$a', 'G', 'Landroid/util/ArrayMap;')
    field('com/apple/android/music/player/viewmodel/PlayerLyricsViewModel$e', 'a', 'I')
    method(adapter, 'o0(I)Z')
    method(adapter, 'F('+holder+')V')
    method('androidx/recyclerview/widget/RecyclerView$D', 'd()I')
    for name in ('i0', 't0'):
        method(adapter, name+'(Lcom/apple/android/music/player/A$a;I)V')
    checks += 1
    if 'smoothScrollToPosition(I)V' in classes['Landroidx/recyclerview/widget/RecyclerView;']['methods']:
        raise RuntimeError('1606 RecyclerView recovery contract changed: inspect the native method')
    word = 'com/apple/android/music/ttml/javanative/model/LyricsWord$LyricsWordNative'
    method(word, 'getHtmlLineText()Ljava/lang/String;')
    word_ptr = 'com/apple/android/music/ttml/javanative/model/LyricsWord$LyricsWordPtr'
    word_vector = 'com/apple/android/music/ttml/javanative/model/LyricsWordVector'
    method(word, 'getWordId()I')
    method('com/apple/android/music/player/u', 'onAnimationUpdate(Landroid/animation/ValueAnimator;)V')
    method('com/apple/android/music/player/A', 'c0(FLcom/apple/android/music/common/views/FullWidthAlphaGradientFlexboxLayout;Lcom/apple/android/music/common/views/FullWidthAlphaGradientFlexboxLayout$a;Lcom/apple/android/music/player/viewmodel/PlayerLyricsViewModel$e;Landroidx/databinding/ViewDataBinding;Z)F', static=True)
    method('com/apple/android/music/player/A', 'd0(Ljava/lang/Integer;[Lcom/apple/android/music/common/views/FullWidthAlphaGradientFlexboxLayout$a;Lcom/apple/android/music/player/viewmodel/PlayerLyricsViewModel$e;Ljava/util/List;Lcom/apple/android/music/common/views/FullWidthAlphaGradientFlexboxLayout;ZZZI)Ljava/util/ArrayList;')
    method(word, 'getLyricsLine()Lcom/apple/android/music/ttml/javanative/model/LyricsLine$LyricsLinePtr;')
    method(word_ptr, 'get()L'+word+';')
    method(word_vector, 'size()J')
    method(word_vector, 'get(J)L'+word_ptr+';')
    checks += 1
    if classes['L'+word+';']['super'] != 'Lcom/apple/android/music/ttml/javanative/model/LyricsTiming;':
        raise RuntimeError('Native word timing superclass changed')
    for name in ('getBegin', 'getEnd'):
        method('com/apple/android/music/ttml/javanative/model/LyricsTiming', name+'()I')
    method(adapter, 'U(Ljava/lang/String;'+flex+'IZI)V')
    method(adapter, 'b0(Lcom/apple/android/music/ttml/javanative/model/LyricsWordVector;Landroid/util/ArrayMap;'+flex+'IIZZ)Landroid/util/ArrayMap;')
    method(adapter, 'y()Lcom/apple/android/music/ttml/javanative/model/SongInfo$SongInfoPtr;')
    method(adapter, 'f(I)I')
    method(adapter, 'E(IZ)I')
    method(adapter, 'k('+holder+'I)V')
    method(adapter, 'l('+holder+'ILjava/util/List;)V')
    method(base, 'B()Z')
    field(adapter, 'p', 'Lcom/apple/android/music/ttml/j;')
    for name in ('d','e'): field(base, name, 'Z')
    for name in ('h','i'): field(base, name, 'Ljava/lang/String;', True)
    field(base+'$b', 'y', 'Ljava/util/LinkedHashSet;')
    field(base+'$b', 'v', 'Ljava/lang/String;')
    field('androidx/recyclerview/widget/RecyclerView$D', 'a', 'Landroid/view/View;')
    method('com/apple/android/music/ttml/j', 'a(I)Lcom/apple/android/music/ttml/javanative/model/LyricsLine$LyricsLinePtr;')
    method('com/apple/android/music/ttml/javanative/model/LyricsLine$LyricsLinePtr', 'get()Lcom/apple/android/music/ttml/javanative/model/LyricsLine$LyricsLineNative;')
    line = 'com/apple/android/music/ttml/javanative/model/LyricsLine$LyricsLineNative'
    method(line, 'getLineId()I')
    method(line, 'getWords()Lcom/apple/android/music/ttml/javanative/model/LyricsWordVector;')
    for name in ('getHtmlPronunciationLineText', 'getHtmlPronunciationBackgroundVocalsLineText'):
        method(line, name+'()Ljava/lang/String;')
    line_adapter = 'com/apple/android/music/player/Y0'
    method(line_adapter, 'y()Lcom/apple/android/music/ttml/javanative/model/SongInfo$SongInfoPtr;')
    method(line_adapter, 'f(I)I')
    method(line_adapter, 'k('+holder+'I)V')
    method(line_adapter, 'l('+holder+'ILjava/util/List;)V')
    method(line_adapter, 'U(Lcom/apple/android/music/player/Y0$j;I)V')
    field(base+'$b', 'u', 'Landroidx/databinding/ViewDataBinding;')
    for name in ('b0', 'Z', 'a0'):
        field('q8/l9', name, 'Lcom/apple/android/music/common/views/CustomTextView;')
    # Style interception relies on the actual auxiliary class inheriting framework setters.
    checks += 1
    auxiliary_type = 'Lcom/apple/android/music/common/views/CustomTextView;'
    while auxiliary_type in classes:
        contract = classes[auxiliary_type]
        if any(m.startswith(('setAlpha(', 'setTextColor(')) for m in contract['methods']):
            raise RuntimeError('Auxiliary view overrides guarded framework setters: '+auxiliary_type)
        auxiliary_type = contract['super']
    if auxiliary_type != 'Landroid/widget/TextView;':
        raise RuntimeError('Auxiliary view does not inherit guarded TextView setters')
    constraints = 'androidx/constraintlayout/widget/ConstraintLayout$b'
    method(constraints, '<init>(Landroid/view/ViewGroup$LayoutParams;)V')
    for name in ('i','j','k','l','t','v'): field(constraints, name, 'I')
    # Research against the SHA-pinned 1606 mapper: these subtitle bindings
    # derive from the two types accepted by A.U; ruby word bindings do not.
    for concrete, parent in (('E9', 'D9'), ('c9', 'b9')):
        checks += 1
        if classes['Lq8/'+concrete+';']['super'] != 'Lq8/'+parent+';':
            raise RuntimeError('Native auxiliary subtitle binding changed: '+concrete)
    vector = 'com/apple/android/mediaservices/javanative/common/StringVector$StringVectorNative'
    method(vector, 'size()J')
    method(vector, 'get(J)Ljava/lang/String;')
    method('com/apple/android/music/playback/util/LocaleUtil', 'matchToSystemLyricsScript(L'+vector+';)Ljava/lang/String;', True)
    resources = subprocess.check_output([str(args.aapt2), 'dump', 'resources', str(args.apk)], text=True)
    for name in ('layout/lyrics_word_karaoke','layout/lyrics_word_karaoke_bg',
                 'layout/lyrics_translation_line_karaoke','layout/lyrics_bg_translation_line_karaoke',
                 'color/white_alpha_35','id/translations_button','id/translations_popup_menu',
                 'id/message_lyrics_process_events'):
        checks += 1
        if not re.search(r'resource 0x[0-9a-f]+ '+name+r'\b', resources):
            raise RuntimeError(f'Missing extension resource: {name}')
    print(f'PASS: {checks} pronunciation/menu extension contracts for {version[1]}')


if __name__ == '__main__':
    main()
