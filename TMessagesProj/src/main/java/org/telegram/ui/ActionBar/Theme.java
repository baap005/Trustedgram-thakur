/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.ActionBar;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.graphics.drawable.shapes.RectShape;
import android.graphics.drawable.shapes.RoundRectShape;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.StateSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Bitmaps;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.time.SunDate;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_keyboard;
import org.telegram.ui.BlurSettingsBottomSheet;
import org.telegram.ui.Cells.BaseCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AudioVisualizerDrawable;
import org.telegram.ui.Components.BackgroundGradientDrawable;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ChoosingStickerStatusDrawable;
import org.telegram.ui.Components.CombinedDrawable;
import org.telegram.ui.Components.FragmentContextViewWavesDrawable;
import org.telegram.ui.Components.LinkPath;
import org.telegram.ui.Components.MotionBackgroundDrawable;
import org.telegram.ui.Components.MsgClockDrawable;
import org.telegram.ui.Components.PathAnimator;
import org.telegram.ui.Components.PlayingGameDrawable;
import org.telegram.ui.Components.Premium.PremiumGradient;
import org.telegram.ui.Components.RLottieDrawable;
import org.telegram.ui.Components.RecordStatusDrawable;
import org.telegram.ui.Components.RoundStatusDrawable;
import org.telegram.ui.Components.ScamDrawable;
import org.telegram.ui.Components.SendingFileDrawable;
import org.telegram.ui.Components.StatusDrawable;
import org.telegram.ui.Components.ThemeEditorView;
import org.telegram.ui.Components.TypingDotsDrawable;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.RoundVideoProgressShadow;
import org.telegram.ui.ThemeActivity;
import org.telegram.ui.ThemePreviewActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

public class Theme {

    public static final String DEFAULT_BACKGROUND_SLUG = "d";
    public static final String THEME_BACKGROUND_SLUG = "t";
    public static final String COLOR_BACKGROUND_SLUG = "c";

    public static final int MSG_OUT_COLOR_BLACK = 0xff212121;
    public static final int MSG_OUT_COLOR_WHITE = 0xffffffff;
    public static final int default_shadow_color = ColorUtils.setAlphaComponent(Color.BLACK, 27);
    public static boolean disallowChangeServiceMessageColor;

    public static void applyDefaultShadow(Paint paint) {
        paint.setShadowLayer(dpf2(1), 0, dpf2(0.33f), default_shadow_color);
    }

    public static Paint getThemePaint(String key, ResourcesProvider resourcesProvider) {
        if (resourcesProvider != null) {
            final Paint paint = resourcesProvider.getPaint(key);
            if (paint != null) {
                return paint;
            }
        }
        return getThemePaint(key);
    }

    public static ColorFilter getAnimatedEmojiColorFilter(ResourcesProvider resourcesProvider) {
        if (resourcesProvider != null) {
            return resourcesProvider.getAnimatedEmojiColorFilter();
        }
        return Theme.chat_animatedEmojiTextColorFilter;
    }

    public static class BackgroundDrawableSettings {

        public Drawable wallpaper;
        public Drawable themedWallpaper;
        public Boolean isWallpaperMotion;
        public Boolean isPatternWallpaper;
        public Boolean isCustomTheme;
    }

    public static class PatternsLoader implements NotificationCenter.NotificationCenterDelegate {

        private static class LoadingPattern {
            public TLRPC.TL_wallPaper pattern;
            public ArrayList<ThemeAccent> accents = new ArrayList<>();
        }

        private int account = UserConfig.selectedAccount;
        private HashMap<String, LoadingPattern> watingForLoad;
        private static PatternsLoader loader;

        public static void createLoader(boolean force) {
            if (loader != null && !force) {
                return;
            }
            ArrayList<ThemeAccent> accentsToLoad = null;
            for (int b = 0; b < 5; b++) {
                String key;
                switch (b) {
                    case 0:
                        key = "Blue";
                        break;
                    case 1:
                        key = "Dark Blue";
                        break;
                    case 2:
                        key = "Arctic Blue";
                        break;
                    case 3:
                        key = "Day";
                        break;
                    case 4:
                    default:
                        key = "Night";
                        break;
                }
                ThemeInfo info = themesDict.get(key);
                if (info == null || info.themeAccents == null || info.themeAccents.isEmpty()) {
                    continue;
                }
                for (int a = 0, N = info.themeAccents.size(); a < N; a++) {
                    ThemeAccent accent = info.themeAccents.get(a);
                    if (accent.id == DEFALT_THEME_ACCENT_ID || TextUtils.isEmpty(accent.patternSlug)) {
                        continue;
                    }
                    if (accentsToLoad == null) {
                        accentsToLoad = new ArrayList<>();
                    }
                    accentsToLoad.add(accent);
                }
            }
            loader = new PatternsLoader(accentsToLoad);
        }

        private PatternsLoader(ArrayList<ThemeAccent> accents) {
            if (accents == null) {
                return;
            }
            Utilities.globalQueue.postRunnable(() -> {
                ArrayList<String> slugs = null;
                for (int a = 0, N = accents.size(); a < N; a++) {
                    ThemeAccent accent = accents.get(a);
                    File wallpaper = accent.getPathToWallpaper();
                    if (wallpaper != null && wallpaper.length() > 0) {
                        accents.remove(a);
                        a--;
                        N--;
                        continue;
                    }
                    if (slugs == null) {
                        slugs = new ArrayList<>();
                    }
                    if (slugs.contains(accent.patternSlug)) {
                        continue;
                    }
                    slugs.add(accent.patternSlug);
                }
                if (slugs == null) {
                    return;
                }
                TL_account.getMultiWallPapers req = new TL_account.getMultiWallPapers();
                for (int a = 0, N = slugs.size(); a < N; a++) {
                    TLRPC.TL_inputWallPaperSlug slug = new TLRPC.TL_inputWallPaperSlug();
                    slug.slug = slugs.get(a);
                    req.wallpapers.add(slug);
                }
                ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                    if (response instanceof Vector) {
                        Vector res = (Vector) response;
                        ArrayList<ThemeAccent> createdAccents = null;
                        for (int b = 0, N2 = res.objects.size(); b < N2; b++) {
                            TLRPC.WallPaper object = (TLRPC.WallPaper) res.objects.get(b);
                            if (!(object instanceof TLRPC.TL_wallPaper)) {
                                continue;
                            }
                            TLRPC.TL_wallPaper wallPaper = (TLRPC.TL_wallPaper) object;
                            if (wallPaper.pattern) {
                                File patternPath = FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(wallPaper.document, true);
                                Boolean exists = null;
                                Bitmap patternBitmap = null;
                                for (int a = 0, N = accents.size(); a < N; a++) {
                                    ThemeAccent accent = accents.get(a);
                                    if (accent.patternSlug.equals(wallPaper.slug)) {
                                        if (exists == null) {
                                            exists = patternPath.exists();
                                        }
                                        if (patternBitmap != null || exists) {
                                            patternBitmap = createWallpaperForAccent(patternBitmap, "application/x-tgwallpattern".equals(wallPaper.document.mime_type), patternPath, accent);
                                            if (createdAccents == null) {
                                                createdAccents = new ArrayList<>();
                                            }
                                            createdAccents.add(accent);
                                        } else {
                                            String key = FileLoader.getAttachFileName(wallPaper.document);
                                            if (watingForLoad == null) {
                                                watingForLoad = new HashMap<>();
                                            }
                                            LoadingPattern loadingPattern = watingForLoad.get(key);
                                            if (loadingPattern == null) {
                                                loadingPattern = new LoadingPattern();
                                                loadingPattern.pattern = wallPaper;
                                                watingForLoad.put(key, loadingPattern);
                                            }
                                            loadingPattern.accents.add(accent);
                                        }
                                    }
                                }
                                if (patternBitmap != null) {
                                    patternBitmap.recycle();
                                }
                            }
                        }
                        checkCurrentWallpaper(createdAccents, true);
                    }
                });
            });
        }

        private void checkCurrentWallpaper(ArrayList<ThemeAccent> accents, boolean load) {
            AndroidUtilities.runOnUIThread(() -> checkCurrentWallpaperInternal(accents, load));
        }

        private void checkCurrentWallpaperInternal(ArrayList<ThemeAccent> accents, boolean load) {
            if (accents != null && currentTheme.themeAccents != null && !currentTheme.themeAccents.isEmpty()) {
                if (accents.contains(currentTheme.getAccent(false))) {
                    reloadWallpaper(true);
                }
            }
            if (load) {
                if (watingForLoad != null) {
                    NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoaded);
                    NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoadFailed);
                    for (HashMap.Entry<String, LoadingPattern> entry : watingForLoad.entrySet()) {
                        LoadingPattern loadingPattern = entry.getValue();
                        FileLoader.getInstance(account).loadFile(ImageLocation.getForDocument(loadingPattern.pattern.document), "wallpaper", null, FileLoader.PRIORITY_LOW, 1);
                    }
                }
            } else {
                if (watingForLoad == null || watingForLoad.isEmpty()) {
                    NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoaded);
                    NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoadFailed);
                }
            }
        }

        private Bitmap createWallpaperForAccent(Bitmap patternBitmap, boolean svg, File patternPath, ThemeAccent accent) {
            try {
                File toFile = accent.getPathToWallpaper();
                if (toFile == null) {
                    return null;
                }
                ThemeInfo themeInfo = accent.parentTheme;
                SparseIntArray values = getThemeFileValues(null, themeInfo.assetName, null);
                checkIsDark(values, themeInfo);
                int backgroundAccent = accent.accentColor;

                int backgroundColor = (int) accent.backgroundOverrideColor;

                int backgroundGradientColor1 = (int) accent.backgroundGradientOverrideColor1;
                if (backgroundGradientColor1 == 0 && accent.backgroundGradientOverrideColor1 == 0) {
                    if (backgroundColor != 0) {
                        backgroundAccent = backgroundColor;
                    }
                    int color = values.get(key_chat_wallpaper_gradient_to1);
                    if (color != 0) {
                        backgroundGradientColor1 = changeColorAccent(themeInfo, backgroundAccent, color);
                    }
                } else {
                    backgroundAccent = 0;
                }

                int backgroundGradientColor2 = (int) accent.backgroundGradientOverrideColor2;
                if (backgroundGradientColor2 == 0 && accent.backgroundGradientOverrideColor2 == 0) {
                    int color = values.get(key_chat_wallpaper_gradient_to2);
                    if (color != 0) {
                        backgroundGradientColor2 = changeColorAccent(themeInfo, backgroundAccent, color);
                    }
                }

                int backgroundGradientColor3 = (int) accent.backgroundGradientOverrideColor3;
                if (backgroundGradientColor3 == 0 && accent.backgroundGradientOverrideColor3 == 0) {
                    int color = values.get(key_chat_wallpaper_gradient_to3);
                    if (color != 0) {
                        backgroundGradientColor3 = changeColorAccent(themeInfo, backgroundAccent, color);
                    }
                }

                if (backgroundColor == 0) {
                    int color = values.get(key_chat_wallpaper);
                    if (color != 0) {
                        backgroundColor = changeColorAccent(themeInfo, backgroundAccent, color);
                    }
                }

                Drawable background;
                int patternColor;
                if (backgroundGradientColor2 != 0) {
                    background = null;
                    patternColor = MotionBackgroundDrawable.getPatternColor(backgroundColor, backgroundGradientColor1, backgroundGradientColor2, backgroundGradientColor3);
                } else if (backgroundGradientColor1 != 0) {
                    BackgroundGradientDrawable.Orientation orientation = BackgroundGradientDrawable.getGradientOrientation(accent.backgroundRotation);
                    background = new BackgroundGradientDrawable(orientation, new int[]{backgroundColor, backgroundGradientColor1});
                    patternColor = AndroidUtilities.getPatternColor(AndroidUtilities.getAverageColor(backgroundColor, backgroundGradientColor1));
                } else {
                    background = new ColorDrawable(backgroundColor);
                    patternColor = AndroidUtilities.getPatternColor(backgroundColor);
                }

                if (patternBitmap == null) {
                    int w = Math.min(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y);
                    int h = Math.max(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y);
                    if (svg) {
                        patternBitmap = SvgHelper.getBitmap(patternPath, w, h, false, SvgHelper.ScaleMode.ByWidth);
                    } else {
                        patternBitmap = loadScreenSizedBitmap(new FileInputStream(patternPath), 0);
                    }
                }

                if (background != null) {
                    Bitmap dst = Bitmap.createBitmap(patternBitmap.getWidth(), patternBitmap.getHeight(), Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(dst);
                    if (background != null) {
                        background.setBounds(0, 0, patternBitmap.getWidth(), patternBitmap.getHeight());
                        background.draw(canvas);
                    }

                    Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
                    paint.setColorFilter(new PorterDuffColorFilter(patternColor, PorterDuff.Mode.SRC_IN));
                    paint.setAlpha((int) (255 * Math.abs(accent.patternIntensity)));
                    canvas.drawBitmap(patternBitmap, 0, 0, paint);

                    FileOutputStream stream = new FileOutputStream(toFile);
                    dst.compress(Bitmap.CompressFormat.JPEG, 87, stream);
                } else {
                    FileOutputStream stream = new FileOutputStream(toFile);
                    patternBitmap.compress(Bitmap.CompressFormat.PNG, 87, stream);
                    stream.close();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            return patternBitmap;
        }

        @Override
        public void didReceivedNotification(int id, int account, Object... args) {
            if (watingForLoad == null) {
                return;
            }
            if (id == NotificationCenter.fileLoaded) {
                String location = (String) args[0];
                LoadingPattern loadingPattern = watingForLoad.remove(location);
                if (loadingPattern != null) {
                    Utilities.globalQueue.postRunnable(() -> {
                        ArrayList<ThemeAccent> createdAccents = null;
                        TLRPC.TL_wallPaper wallPaper = loadingPattern.pattern;
                        File patternPath = FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(wallPaper.document, true);
                        Bitmap patternBitmap = null;
                        for (int a = 0, N = loadingPattern.accents.size(); a < N; a++) {
                            ThemeAccent accent = loadingPattern.accents.get(a);
                            if (accent.patternSlug.equals(wallPaper.slug)) {
                                patternBitmap = createWallpaperForAccent(patternBitmap, "application/x-tgwallpattern".equals(wallPaper.document.mime_type), patternPath, accent);
                                if (createdAccents == null) {
                                    createdAccents = new ArrayList<>();
                                    createdAccents.add(accent);
                                }
                            }
                        }
                        if (patternBitmap != null) {
                            patternBitmap.recycle();
                        }
                        checkCurrentWallpaper(createdAccents, false);
                    });
                }
            } else if (id == NotificationCenter.fileLoadFailed) {
                String location = (String) args[0];
                if (watingForLoad.remove(location) != null) {
                    checkCurrentWallpaper(null, false);
                }
            }
        }
    }

    public static class ThemeAccent {
        public int id;

        public ThemeInfo parentTheme;

        public int accentColor;
        public int accentColor2;
        public int myMessagesAccentColor;
        public int myMessagesGradientAccentColor1;
        public int myMessagesGradientAccentColor2;
        public int myMessagesGradientAccentColor3;
        public boolean myMessagesAnimated;
        public long backgroundOverrideColor;
        public long backgroundGradientOverrideColor1;
        public long backgroundGradientOverrideColor2;
        public long backgroundGradientOverrideColor3;
        public int backgroundRotation = 45;
        public String patternSlug = "";
        public float patternIntensity;
        public boolean patternMotion;

        public TLRPC.TL_theme info;
        public TLRPC.TL_wallPaper pattern;
        public int account;

        public String pathToFile;
        public String uploadingThumb;
        public String uploadingFile;
        public TLRPC.InputFile uploadedThumb;
        public TLRPC.InputFile uploadedFile;

        public OverrideWallpaperInfo overrideWallpaper;
        public boolean isDefault;

        ThemeAccent() {

        }

        public ThemeAccent(ThemeAccent other) {
            this.id = other.id;
            this.parentTheme = other.parentTheme;
            this.accentColor = other.accentColor;
            this.myMessagesAccentColor = other.myMessagesAccentColor;
            this.myMessagesGradientAccentColor1 = other.myMessagesGradientAccentColor1;
            this.myMessagesGradientAccentColor2 = other.myMessagesGradientAccentColor2;
            this.myMessagesGradientAccentColor3 = other.myMessagesGradientAccentColor3;
            this.myMessagesAnimated = other.myMessagesAnimated;
            this.backgroundOverrideColor = other.backgroundOverrideColor;
            this.backgroundGradientOverrideColor1 = other.backgroundGradientOverrideColor1;
            this.backgroundGradientOverrideColor2 = other.backgroundGradientOverrideColor2;
            this.backgroundGradientOverrideColor3 = other.backgroundGradientOverrideColor3;
            this.backgroundRotation = other.backgroundRotation;
            this.patternSlug = other.patternSlug;
            this.patternIntensity = other.patternIntensity;
            this.patternMotion = other.patternMotion;
            this.info = other.info;
            this.pattern = other.pattern;
            this.account = other.account;
            this.pathToFile = other.pathToFile;
            this.uploadingThumb = other.uploadingThumb;
            this.uploadingFile = other.uploadingFile;
            this.uploadedThumb = other.uploadedThumb;
            this.uploadedFile = other.uploadedFile;
            this.overrideWallpaper = other.overrideWallpaper;
        }

        public boolean fillAccentColors(SparseIntArray currentColorsNoAccent, SparseIntArray currentColors) {
            boolean isMyMessagesGradientColorsNear = false;

            float[] hsvTemp1 = getTempHsv(1);
            float[] hsvTemp2 = getTempHsv(2);

            Color.colorToHSV(parentTheme.accentBaseColor, hsvTemp1);
            Color.colorToHSV(accentColor, hsvTemp2);
            boolean isDarkTheme = parentTheme.isDark();

            if (accentColor != parentTheme.accentBaseColor || accentColor2 != 0) {
                for (int i = 0; i < defaultColors.length; i++) {
                    int key = i;
                    if (themeAccentExclusionKeys.contains(key)) {
                        continue;
                    }
                    int index = currentColorsNoAccent.indexOfKey(key);
                    int color;
                    if (index < 0) {
                        int fallbackKey = fallbackKeys.get(key, -1);
                        if (fallbackKey >= 0 && currentColorsNoAccent.indexOfKey(fallbackKey) >= 0) {
                            continue;
                        }
                        color = defaultColors[key];
                    } else {
                        color = currentColorsNoAccent.valueAt(index);
                    }
                    int newColor = changeColorAccent(hsvTemp1, hsvTemp2, color, isDarkTheme, color);
                    if (newColor != color) {
                        currentColors.put(key, newColor);
                    }
                }
            }
            int myMessagesAccent = myMessagesAccentColor;
            if ((myMessagesAccentColor != 0 || accentColor != 0) && myMessagesGradientAccentColor1 != 0) {
                int firstColor = myMessagesAccentColor != 0 ? myMessagesAccentColor : accentColor;
                int color = currentColorsNoAccent.get(key_chat_outBubble);
                if (color == 0) {
                    color = defaultColors[key_chat_outBubble];
                }
                int newColor = changeColorAccent(hsvTemp1, hsvTemp2, color, isDarkTheme, color);
                int distance1 = AndroidUtilities.getColorDistance(firstColor, newColor);
                int distance2 = AndroidUtilities.getColorDistance(firstColor, myMessagesGradientAccentColor1);

                boolean useBlackText;
                if (myMessagesGradientAccentColor2 != 0) {
                    int averageColor = AndroidUtilities.getAverageColor(myMessagesAccentColor, myMessagesGradientAccentColor1);
                    averageColor = AndroidUtilities.getAverageColor(averageColor, myMessagesGradientAccentColor2);
                    if (myMessagesGradientAccentColor3 != 0) {
                        averageColor = AndroidUtilities.getAverageColor(averageColor, myMessagesGradientAccentColor3);
                    }
                    useBlackText = AndroidUtilities.computePerceivedBrightness(averageColor) > 0.705f;
                } else {
                    useBlackText = useBlackText(myMessagesAccentColor, myMessagesGradientAccentColor1);
                }
                if (useBlackText) {
                    isMyMessagesGradientColorsNear = distance1 <= 35000 && distance2 <= 35000;
                } else {
                    isMyMessagesGradientColorsNear = false;
                }
                myMessagesAccent = getAccentColor(hsvTemp1, color, firstColor);
            }
            boolean changeMyMessagesColors = (myMessagesAccent != 0 && (parentTheme.accentBaseColor != 0 && myMessagesAccent != parentTheme.accentBaseColor || accentColor != 0 && accentColor != myMessagesAccent));
            if (changeMyMessagesColors || accentColor2 != 0) {
                if (accentColor2 != 0) {
                    Color.colorToHSV(accentColor2, hsvTemp2);
                } else {
                    Color.colorToHSV(myMessagesAccent, hsvTemp2);
                }

                for (int key = myMessagesStartIndex; key < myMessagesEndIndex; key++) {
                    int index = currentColorsNoAccent.indexOfKey(key);
                    int color;
                    if (index < 0) {
                        int fallbackKey = fallbackKeys.get(key, -1);
                        if (fallbackKey >= 0 && currentColorsNoAccent.get(fallbackKey, -1) >= 0) {
                            continue;
                        }
                        color = defaultColors[key];
                    } else {
                        color = currentColorsNoAccent.valueAt(index);
                    }
                    int newColor = changeColorAccent(hsvTemp1, hsvTemp2, color, isDarkTheme, color);
                    if (newColor != color) {
                        currentColors.put(key, newColor);
                    }
                }
                for (int key : myMessagesAccentExtraKeys) {
                    final int index = currentColorsNoAccent.indexOfKey(key);
                    final int color = index < 0 ? defaultColors[key] : currentColorsNoAccent.valueAt(index);
                    final int newColor = changeColorAccent(hsvTemp1, hsvTemp2, color, isDarkTheme, color);
                    if (newColor != color) {
                        currentColors.put(key, newColor);
                    }
                }

                if (changeMyMessagesColors) {
                    Color.colorToHSV(myMessagesAccent, hsvTemp2);
                    for (int key = myMessagesBubblesStartIndex; key < myMessagesBubblesEndIndex; key++) {
                        int index = currentColorsNoAccent.indexOfKey(key);
                        int color;
                        if (index < 0) {
                            int fallbackKey = fallbackKeys.get(key, -1);
                            if (fallbackKey >= 0 && currentColorsNoAccent.get(fallbackKey, -1) >= 0) {
                                continue;
                            }
                            color = defaultColors[key];
                        } else {
                            color = currentColorsNoAccent.valueAt(index);
                        }
                        int newColor = changeColorAccent(hsvTemp1, hsvTemp2, color, isDarkTheme, color);
                        if (newColor != color) {
                            currentColors.put(key, newColor);
                        }
                    }
                }
            }
            if (!isMyMessagesGradientColorsNear) {
                if (myMessagesGradientAccentColor1 != 0) {
                    int textColor;
                    int subTextColor;
                    int seekbarColor;
                    boolean useBlackText;
                    if (myMessagesGradientAccentColor2 != 0) {
                        int color = AndroidUtilities.getAverageColor(myMessagesAccentColor, myMessagesGradientAccentColor1);
                        color = AndroidUtilities.getAverageColor(color, myMessagesGradientAccentColor2);
                        if (myMessagesGradientAccentColor3 != 0) {
                            color = AndroidUtilities.getAverageColor(color, myMessagesGradientAccentColor3);
                        }
                        useBlackText = AndroidUtilities.computePerceivedBrightness(color) > 0.705f;
                    } else {
                        useBlackText = useBlackText(myMessagesAccentColor, myMessagesGradientAccentColor1);
                    }
                    if (useBlackText) {
                        textColor = MSG_OUT_COLOR_BLACK;
                        subTextColor = 0xff555555;
                        seekbarColor = 0x4d000000;
                    } else {
                        textColor = MSG_OUT_COLOR_WHITE;
                        subTextColor = 0xffeeeeee;
                        seekbarColor = 0x4dffffff;
                    }

                    if (accentColor2 == 0) {
                        currentColors.put(key_chat_outAudioProgress, seekbarColor);
                        currentColors.put(key_chat_outAudioSelectedProgress, seekbarColor);
                        currentColors.put(key_chat_outAudioSeekbar, seekbarColor);
                        currentColors.put(key_chat_outAudioCacheSeekbar, seekbarColor);
                        currentColors.put(key_chat_outAudioSeekbarSelected, seekbarColor);
                        currentColors.put(key_chat_outAudioSeekbarFill, textColor);

                        currentColors.put(key_chat_outVoiceSeekbar, seekbarColor);
                        currentColors.put(key_chat_outVoiceSeekbarSelected, seekbarColor);
                        currentColors.put(key_chat_outVoiceSeekbarFill, textColor);

                        currentColors.put(key_chat_messageLinkOut, textColor);
                        currentColors.put(key_chat_outForwardedNameText, textColor);
                        currentColors.put(key_chat_outViaBotNameText, textColor);
                        currentColors.put(key_chat_outReplyLine, textColor);
                        currentColors.put(key_chat_outReplyLine2, textColor);
                        currentColors.put(key_chat_outReplyNameText, textColor);

                        currentColors.put(key_chat_outPreviewLine, textColor);
                        currentColors.put(key_chat_outSiteNameText, textColor);
                        currentColors.put(key_chat_outInstant, textColor);
                        currentColors.put(key_chat_outInstantSelected, textColor);
                        currentColors.put(key_chat_outPreviewInstantText, textColor);

                        currentColors.put(key_chat_outViews, textColor);
                        currentColors.put(key_chat_outViewsSelected, textColor);

                        currentColors.put(key_chat_outAudioTitleText, textColor);
                        currentColors.put(key_chat_outFileNameText, textColor);
                        currentColors.put(key_chat_outContactNameText, textColor);

                        currentColors.put(key_chat_outAudioPerformerText, textColor);
                        currentColors.put(key_chat_outAudioPerformerSelectedText, textColor);

                        currentColors.put(key_chat_outSentCheck, textColor);
                        currentColors.put(key_chat_outSentCheckSelected, textColor);

                        currentColors.put(key_chat_outSentCheckRead, textColor);
                        currentColors.put(key_chat_outSentCheckReadSelected, textColor);

                        currentColors.put(key_chat_outSentClock, textColor);
                        currentColors.put(key_chat_outSentClockSelected, textColor);

                        currentColors.put(key_chat_outMenu, textColor);
                        currentColors.put(key_chat_outMenuSelected, textColor);

                        currentColors.put(key_chat_outTimeText, textColor);
                        currentColors.put(key_chat_outTimeSelectedText, textColor);

                        currentColors.put(key_chat_outAudioDurationText, subTextColor);
                        currentColors.put(key_chat_outAudioDurationSelectedText, subTextColor);

                        currentColors.put(key_chat_outContactPhoneText, subTextColor);
                        currentColors.put(key_chat_outContactPhoneSelectedText, subTextColor);

                        currentColors.put(key_chat_outFileInfoText, subTextColor);
                        currentColors.put(key_chat_outFileInfoSelectedText, subTextColor);

                        currentColors.put(key_chat_outVenueInfoText, subTextColor);
                        currentColors.put(key_chat_outVenueInfoSelectedText, subTextColor);


                        currentColors.put(key_chat_outLoader, textColor);
                        currentColors.put(key_chat_outLoaderSelected, textColor);
                        currentColors.put(key_chat_outFileProgress, myMessagesAccentColor);
                        currentColors.put(key_chat_outFileProgressSelected, myMessagesAccentColor);
                        currentColors.put(key_chat_outMediaIcon, myMessagesAccentColor);
                        currentColors.put(key_chat_outMediaIconSelected, myMessagesAccentColor);
                    }

                    currentColors.put(key_chat_outReplyMessageText, textColor);
                    currentColors.put(key_chat_outReplyMediaMessageText, textColor);
                    currentColors.put(key_chat_outReplyMediaMessageSelectedText, textColor);
                    currentColors.put(key_chat_messageTextOut, textColor);
                }
            }
            if (isMyMessagesGradientColorsNear) {
                int outColor = currentColors.indexOfKey(key_chat_outLoader) >= 0
                        ? currentColors.get(key_chat_outLoader)
                        : Color.TRANSPARENT;
                if (AndroidUtilities.getColorDistance(0xffffffff, outColor) < 5000) {
                    isMyMessagesGradientColorsNear = false;
                }
            }
            if (myMessagesAccentColor != 0 && myMessagesGradientAccentColor1 != 0) {
                currentColors.put(key_chat_outBubble, myMessagesAccentColor);
                currentColors.put(key_chat_outBubbleGradient1, myMessagesGradientAccentColor1);
                if (myMessagesGradientAccentColor2 != 0) {
                    currentColors.put(key_chat_outBubbleGradient2, myMessagesGradientAccentColor2);
                    if (myMessagesGradientAccentColor3 != 0) {
                        currentColors.put(key_chat_outBubbleGradient3, myMessagesGradientAccentColor3);
                    }
                }
                currentColors.put(key_chat_outBubbleGradientAnimated, myMessagesAnimated ? 1 : 0);
            }
            int backgroundOverride = (int) backgroundOverrideColor;
            if (backgroundOverride != 0) {
                currentColors.put(key_chat_wallpaper, backgroundOverride);
            } else if (backgroundOverrideColor != 0) {
                currentColors.delete(key_chat_wallpaper);
            }
            int backgroundGradientOverride1 = (int) backgroundGradientOverrideColor1;
            if (backgroundGradientOverride1 != 0) {
                currentColors.put(key_chat_wallpaper_gradient_to1, backgroundGradientOverride1);
            } else if (backgroundGradientOverrideColor1 != 0) {
                currentColors.delete(key_chat_wallpaper_gradient_to1);
            }
            int backgroundGradientOverride2 = (int) backgroundGradientOverrideColor2;
            if (backgroundGradientOverride2 != 0) {
                currentColors.put(key_chat_wallpaper_gradient_to2, backgroundGradientOverride2);
            } else if (backgroundGradientOverrideColor2 != 0) {
                currentColors.delete(key_chat_wallpaper_gradient_to2);
            }
            int backgroundGradientOverride3 = (int) backgroundGradientOverrideColor3;
            if (backgroundGradientOverride3 != 0) {
                currentColors.put(key_chat_wallpaper_gradient_to3, backgroundGradientOverride3);
            } else if (backgroundGradientOverrideColor3 != 0) {
                currentColors.delete(key_chat_wallpaper_gradient_to3);
            }
            if (backgroundRotation != 45) {
                currentColors.put(key_chat_wallpaper_gradient_rotation, backgroundRotation);
            }

            int outBubble = currentColors.get(key_chat_outBubble);
            if (outBubble == 0) {
                outBubble = getColor(key_chat_outBubble);
            }
            int inBubble = currentColors.get(key_chat_inBubble);
            if (inBubble == 0) {
                inBubble = getColor(key_chat_inBubble);
            }

            int gradientAverageColor = outBubble;
            if (info != null && info.emoticon != null && !isDarkTheme) {
                currentColors.delete(key_chat_selectedBackground);
                gradientAverageColor = averageColor(currentColors, key_chat_wallpaper_gradient_to1, key_chat_wallpaper_gradient_to2, key_chat_wallpaper_gradient_to3);
                if (gradientAverageColor == 0) {
                    gradientAverageColor = averageColor(currentColors, key_chat_wallpaper);
                }
                if (gradientAverageColor == 0) {
                    gradientAverageColor = accentColor;
                }

                int outOverlay = bubbleSelectedOverlay(outBubble, gradientAverageColor);
                currentColors.put(key_chat_outBubbleSelectedOverlay, outOverlay);
                currentColors.put(key_chat_outBubbleGradientSelectedOverlay, outOverlay);
                currentColors.put(key_chat_outBubbleSelected, Theme.blendOver(outBubble, outOverlay));

                int inOverlay = bubbleSelectedOverlay(inBubble, accentColor);
                currentColors.put(key_chat_inBubbleSelectedOverlay, inOverlay);
                currentColors.put(key_chat_inBubbleSelected, Theme.blendOver(inBubble, inOverlay));
            }

            if (!isDarkTheme) {
                currentColors.put(key_chat_inTextSelectionHighlight, textSelectionBackground(false, inBubble, accentColor));
                currentColors.put(key_chat_outTextSelectionHighlight, textSelectionBackground(true, outBubble, accentColor));
                currentColors.put(key_chat_outTextSelectionCursor, textSelectionHandle(outBubble, accentColor));
            }

            float accentHue = getHue(getColor(key_windowBackgroundWhiteBlueText));
            currentColors.put(key_chat_outBubbleLocationPlaceholder, locationPlaceholderColor(accentHue, outBubble, isDarkTheme));
            currentColors.put(key_chat_inBubbleLocationPlaceholder, locationPlaceholderColor(accentHue, inBubble, isDarkTheme));

            int inMsgLink = currentColors.get(key_chat_messageLinkIn);
            if (inMsgLink == 0) {
                inMsgLink = getColor(key_chat_messageLinkIn);
            }
            int outMsgLink = currentColors.get(key_chat_messageLinkOut);
            if (outMsgLink == 0) {
                outMsgLink = getColor(key_chat_messageLinkOut);
            }
            currentColors.put(key_chat_linkSelectBackground, linkSelectionBackground(inMsgLink, inBubble, isDarkTheme));
            currentColors.put(key_chat_outLinkSelectBackground, linkSelectionBackground(outMsgLink, outBubble, isDarkTheme));

            int submenuBackground = currentColors.get(key_actionBarDefaultSubmenuBackground);
            if (submenuBackground == 0) {
                submenuBackground = getColor(key_actionBarDefaultSubmenuBackground);
            }
            currentColors.put(key_actionBarDefaultSubmenuSeparator, Color.argb(
                Color.alpha(submenuBackground),
                Math.max(0, Color.red(submenuBackground) - 10),
                Math.max(0, Color.green(submenuBackground) - 10),
                Math.max(0, Color.blue(submenuBackground) - 10)
            ));

            if (isDarkTheme && currentColors.get(key_chat_outBubbleGradient1) != 0) {
                int outBubbleAverage = averageColor(currentColors, key_chat_outBubbleGradient1, key_chat_outBubbleGradient2, key_chat_outBubbleGradient3);
                Color.colorToHSV(outBubbleAverage, tempHSV);
                tempHSV[1] = Utilities.clamp(tempHSV[1] + .1f, 1, 0);
                tempHSV[2] = Utilities.clamp(tempHSV[2] - .8f, 1, 0);
                currentColors.put(key_chat_outCodeBackground, Color.HSVToColor(0x40, tempHSV));
            } else {
                currentColors.put(key_chat_outCodeBackground, codeBackground(outBubble, isDarkTheme));
            }
            applyCalculatedTableColors(currentColorsNoAccent, currentColors, isDarkTheme);
            applyCalculatedArticleCodeColors(currentColorsNoAccent, currentColors, isDarkTheme);

            return !isMyMessagesGradientColorsNear;
        }

        public void resetAccentColorsForMyMessagesGiftThemeLight(SparseIntArray currentColors) {
            for (int a = Theme.myMessagesBubblesStartIndex; a < Theme.myMessagesBubblesEndIndex; a++) {
                currentColors.delete(a);
                currentColors.put(a, defaultColors[a]);
            }
            for (int a = Theme.myMessagesStartIndex; a < Theme.myMessagesEndIndex; a++) {
                currentColors.delete(a);
                currentColors.put(a, defaultColors[a]);
            }
            for (int a = Theme.myMessages2StartIndex; a < Theme.myMessages2EndIndex; a++) {
                currentColors.delete(a);
                currentColors.put(a, defaultColors[a]);
            }
        }


        private float[] tempHSV = new float[3];
        private int setHue(int color, int hueFromColor) {
            Color.colorToHSV(hueFromColor, tempHSV);
            float hue = tempHSV[0];
            Color.colorToHSV(color, tempHSV);
            if (tempHSV[1] > 0.02f) {
                tempHSV[0] = hue;
            }
            return Color.HSVToColor(Color.alpha(color), tempHSV);
        }
        private float getHue(int color) {
            Color.colorToHSV(color, tempHSV);
            return tempHSV[0];
        }
        private int bubbleSelectedOverlay(int bubble, int accentColor) {
            Color.colorToHSV(accentColor, tempHSV);
            float h = tempHSV[0];
            Color.colorToHSV(bubble, tempHSV);
            if (tempHSV[1] <= 0) {
                tempHSV[0] = h;
            }
            tempHSV[1] = Math.max(0, Math.min(1, tempHSV[1] + .6f));
            tempHSV[2] = Math.max(0, Math.min(1, tempHSV[2] - .05f));
            return Color.HSVToColor(30, tempHSV);
        }
        private int textSelectionBackground(boolean isOut, int bubble, int accentColor) {
            Color.colorToHSV(accentColor, tempHSV);
            float h = tempHSV[0];
            Color.colorToHSV(bubble, tempHSV);
            if (tempHSV[1] <= 0 || tempHSV[0] > 45 && tempHSV[0] < 85) {
                tempHSV[0] = h;
            }
            tempHSV[1] = Math.max(0, Math.min(1, tempHSV[1] + (tempHSV[2] > .85f ? .25f : .45f)));
            tempHSV[2] = Math.max(0, Math.min(1, tempHSV[2] - .15f));
            return Color.HSVToColor(80, tempHSV);
        }
        private int textSelectionHandle(int bubble, int accentColor) {
            Color.colorToHSV(accentColor, tempHSV);
            float h = tempHSV[0];
            Color.colorToHSV(bubble, tempHSV);
            if (tempHSV[1] <= 0 || tempHSV[0] > 45 && tempHSV[0] < 85) {
                tempHSV[0] = h;
            }
            tempHSV[1] = Math.max(0, Math.min(1, tempHSV[1] + .6f));
            tempHSV[2] = Math.max(0, Math.min(1, tempHSV[2] - (tempHSV[2] > .7f ? .25f : .125f)));
            return Theme.blendOver(bubble, Color.HSVToColor(255, tempHSV));
        }
        private int linkSelectionBackground(int linkColor, int bgColor, boolean isDarkTheme) {
            Color.colorToHSV(ColorUtils.blendARGB(linkColor, bgColor, .25f), tempHSV);
            tempHSV[1] = Math.max(0, Math.min(1, tempHSV[1] - .1f));
            tempHSV[2] = Math.max(0, Math.min(1, tempHSV[2] + (isDarkTheme ? .1f : 0)));
            return Color.HSVToColor(0x33, tempHSV);
        }
        private int codeBackground(int bubbleColor, boolean isDarkTheme) {
            Color.colorToHSV(bubbleColor, tempHSV);
            int alpha = 0x20;
            if (isDarkTheme) {
                alpha = 0x40;
                tempHSV[1] = Utilities.clamp(tempHSV[1] - .08f, 1f, 0f);
                tempHSV[2] = .03f;
            } else {
                if (tempHSV[1] <= 0 || tempHSV[2] >= 1 || tempHSV[2] <= 0) {
                    tempHSV[2] = Math.max(0, Math.min(1, tempHSV[2] + -.2f));
                } else {
                    tempHSV[1] = Math.max(0, Math.min(1, tempHSV[1] + .28f));
                    tempHSV[2] = Math.max(0, Math.min(1, tempHSV[2] + -.1f));
                }
            }
            return Color.HSVToColor(alpha, tempHSV);
        }
        private int locationPlaceholderColor(float accentHue, int bubbleColor, boolean isDarkTheme) {
            if (isDarkTheme) {
                return 0x1effffff;
            } else {
                Color.colorToHSV(bubbleColor, tempHSV);
                if (tempHSV[1] <= 0 || tempHSV[2] >= 1 || tempHSV[2] <= 0) {
                    tempHSV[0] = accentHue;
                    tempHSV[1] = .2f;
                } else {
                    tempHSV[0] = MathUtils.clamp(tempHSV[0] + .22f, 0, 1);
                    tempHSV[1] = MathUtils.clamp(tempHSV[1] - .35f, 0, 1);
                }
                tempHSV[2] = MathUtils.clamp(tempHSV[2] - .65f, 0, 1);
                return Color.HSVToColor(0x5a, tempHSV);
            }
        }
        private int averageColor(SparseIntArray colors, int ...keys) {
            int r = 0, g = 0, b = 0, c = 0;
            for (int i = 0; i < keys.length; ++i) {
                if (colors.indexOfKey(keys[i]) < 0) {
                    continue;
                }
                try {
                    int color = colors.get(keys[i]);
                    r += Color.red(color);
                    g += Color.green(color);
                    b += Color.blue(color);
                    c++;
                } catch (Exception ignore) {}
            }
            if (c == 0) {
                return 0;
            }
            return Color.argb(255, r / c, g / c, b / c);
        }

        public File getPathToWallpaper() {
            if (id < 100) {
                return !TextUtils.isEmpty(patternSlug) ? new File(ApplicationLoader.getFilesDirFixed(), String.format(Locale.US, "%s_%d_%s_v5.jpg", parentTheme.getKey(), id, patternSlug)) : null;
            } else {
                return !TextUtils.isEmpty(patternSlug) ? new File(ApplicationLoader.getFilesDirFixed(), String.format(Locale.US, "%s_%d_%s_v8_debug.jpg", parentTheme.getKey(), id, patternSlug)) : null;
            }
        }

        public File saveToFile() {
            File dir = AndroidUtilities.getSharingDirectory();
            dir.mkdirs();
            File path = new File(dir, String.format(Locale.US, "%s_%d.attheme", parentTheme.getKey(), id));

            SparseIntArray currentColorsNoAccent = getThemeFileValues(null, parentTheme.assetName, null);
            SparseIntArray currentColors = currentColorsNoAccent.clone();
            fillAccentColors(currentColorsNoAccent, currentColors);

            String wallpaperLink = null;

            if (!TextUtils.isEmpty(patternSlug)) {
                StringBuilder modes = new StringBuilder();
                if (patternMotion) {
                    modes.append("motion");
                }
                int selectedColor = currentColors.get(key_chat_wallpaper);
                if (selectedColor == 0) {
                    selectedColor = 0xffffffff;
                }
                int selectedGradientColor1 = currentColors.get(key_chat_wallpaper_gradient_to1);
                if (selectedGradientColor1 == 0) {
                    selectedGradientColor1 = 0;
                }
                int selectedGradientColor2 = currentColors.get(key_chat_wallpaper_gradient_to2);
                if (selectedGradientColor2 == 0) {
                    selectedGradientColor2 = 0;
                }
                int selectedGradientColor3 = currentColors.get(key_chat_wallpaper_gradient_to3);
                if (selectedGradientColor3 == 0) {
                    selectedGradientColor3 = 0;
                }
                int selectedGradientRotation = currentColors.get(key_chat_wallpaper_gradient_rotation);
                if (selectedGradientRotation == 0) {
                    selectedGradientRotation = 45;
                }
                String color = String.format("%02x%02x%02x", (byte) (selectedColor >> 16) & 0xff, (byte) (selectedColor >> 8) & 0xff, (byte) (selectedColor & 0xff)).toLowerCase();
                String color2 = selectedGradientColor1 != 0 ? String.format("%02x%02x%02x", (byte) (selectedGradientColor1 >> 16) & 0xff, (byte) (selectedGradientColor1 >> 8) & 0xff, (byte) (selectedGradientColor1 & 0xff)).toLowerCase() : null;
                String color3 = selectedGradientColor2 != 0 ? String.format("%02x%02x%02x", (byte) (selectedGradientColor2 >> 16) & 0xff, (byte) (selectedGradientColor2 >> 8) & 0xff, (byte) (selectedGradientColor2 & 0xff)).toLowerCase() : null;
                String color4 = selectedGradientColor3 != 0 ? String.format("%02x%02x%02x", (byte) (selectedGradientColor3 >> 16) & 0xff, (byte) (selectedGradientColor3 >> 8) & 0xff, (byte) (selectedGradientColor3 & 0xff)).toLowerCase() : null;
                if (color2 != null && color3 != null) {
                    if (color4 != null) {
                        color += "~" + color2 + "~" + color3 + "~" + color4;
                    } else {
                        color += "~" + color2 + "~" + color3;
                    }
                } else if (color2 != null) {
                    color += "-" + color2;
                    color += "&rotation=" + selectedGradientRotation;
                }
                wallpaperLink = "https://attheme.org?slug=" + patternSlug + "&intensity=" + (int) (patternIntensity * 100) + "&bg_color=" + color;
                if (modes.length() > 0) {
                    wallpaperLink += "&mode=" + modes.toString();
                }
            }

            StringBuilder result = new StringBuilder();
            for (int i = 0; i < currentColors.size(); i++) {
                int key = currentColors.keyAt(i);
                int color = currentColors.valueAt(i);
                if (wallpaperLink != null) {
                    if (key_chat_wallpaper == key || key_chat_wallpaper_gradient_to1 == key || key_chat_wallpaper_gradient_to2 == key || key_chat_wallpaper_gradient_to3 == key) {
                        continue;
                    }
                }
                result.append(key).append("=").append(color).append("\n");
            }
            FileOutputStream stream = null;
            try {
                stream = new FileOutputStream(path);
                stream.write(AndroidUtilities.getStringBytes(result.toString()));
                if (!TextUtils.isEmpty(wallpaperLink)) {
                    stream.write(AndroidUtilities.getStringBytes("WLS=" + wallpaperLink + "\n"));
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                try {
                    if (stream != null) {
                        stream.close();
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            return path;
        }
    }

    public static int blendOver(int A, int B) { // B over A
        // over operator: https://en.wikipedia.org/wiki/Alpha_compositing#Description
        float aB = Color.alpha(B) / 255f,
              aA = Color.alpha(A) / 255f,
              aC = (aB + aA * (1 - aB));
        if (aC == 0f)
            return 0;
        return Color.argb(
            (int) (aC * 255),
            (int) ((Color.red(B) * aB + Color.red(A) * aA * (1 - aB)) / aC),
            (int) ((Color.green(B) * aB + Color.green(A) * aA * (1 - aB)) / aC),
            (int) ((Color.blue(B) * aB + Color.blue(A) * aA * (1 - aB)) / aC)
        );
    }

    public static int adaptHue(int color, int hueFromColor) {
        float hue, sat;
        float[] tempHSV = getTempHsv(5);
        Color.colorToHSV(hueFromColor, tempHSV);
        hue = tempHSV[0];
        sat = tempHSV[1];
        Color.colorToHSV(color, tempHSV);
        tempHSV[0] = hue;
        tempHSV[1] = AndroidUtilities.lerp(tempHSV[1], sat, .25f);
        return Color.HSVToColor(Color.alpha(color), tempHSV);
    }

    public static int adaptHSV(int color, float sat, float val) {
        float[] tempHSV = getTempHsv(5);
        Color.colorToHSV(color, tempHSV);
        if (tempHSV[1] > .1f && tempHSV[1] < .9f) { // otherwise, saturation would reveal some random hue there
            tempHSV[1] = MathUtils.clamp(tempHSV[1] + sat, 0, 1);
        }
        tempHSV[2] = MathUtils.clamp(tempHSV[2] + val, 0, 1);
        return Color.HSVToColor(Color.alpha(color), tempHSV);
    }

    public static int percentSV(int color1, int color2, float satT, float valT) {
        float[] tempHSV = getTempHsv(5);
        Color.colorToHSV(color2, tempHSV);
        float sat2 = tempHSV[1];
        float val2 = tempHSV[2];
        Color.colorToHSV(color1, tempHSV);
        tempHSV[1] = MathUtils.clamp(AndroidUtilities.lerp(tempHSV[1], sat2, satT), 0, 1);
        tempHSV[2] = MathUtils.clamp(AndroidUtilities.lerp(tempHSV[2], val2, valT), 0, 1);
        return Color.HSVToColor(
            AndroidUtilities.lerp(Color.alpha(color1), Color.alpha(color2), .85f),
            tempHSV
        );
    }

    public static int capAlpha(int color, float maxAlpha) {
        return ColorUtils.setAlphaComponent(color, MathUtils.clamp(Color.alpha(color), 0, (int) (0xFF * maxAlpha)));
    }

    public static int multAlpha(int color, float multiply) {
        if (multiply == 1f)
            return color;
        return ColorUtils.setAlphaComponent(color, MathUtils.clamp((int) (Color.alpha(color) * multiply), 0, 0xFF));
    }

    public static int reverseBlendOver(float ax, int y, int z) {
        float ay = Color.alpha(y) / 255f,
              az = Color.alpha(z) / 255f;
        return Color.argb(
            (int) (ax * 255),
            (int) ((Color.red(y) * ay * (1 - ax) - Color.red(z) * az) / ax),
            (int) ((Color.green(y) * ay * (1 - ax) - Color.green(z) * az) / ax),
            (int) ((Color.blue(y) * ay * (1 - ax) - Color.blue(z) * az) / ax)
        );
    }

    public static class OverrideWallpaperInfo {
        public String fileName = "";
        public String originalFileName = "";
        public String slug = "";
        public int color;
        public int gradientColor1;
        public int gradientColor2;
        public int gradientColor3;
        public int rotation;
        public boolean isBlurred;
        public boolean isMotion;
        public float intensity;
        public long wallpaperId;
        public long accessHash;
        public long dialogId;
        public boolean forBoth;

        public ThemeInfo parentTheme;
        public ThemeAccent parentAccent;
        public float uploadingProgress;
        public ArrayList<Integer> requestIds;
        public TLRPC.WallPaper prevUserWallpaper;

        public OverrideWallpaperInfo() {

        }

        public OverrideWallpaperInfo(OverrideWallpaperInfo info, ThemeInfo themeInfo, ThemeAccent accent) {
            slug = info.slug;
            color = info.color;
            gradientColor1 = info.gradientColor1;
            gradientColor2 = info.gradientColor2;
            gradientColor3 = info.gradientColor3;
            rotation = info.rotation;
            isBlurred = info.isBlurred;
            isMotion = info.isMotion;
            intensity = info.intensity;
            parentTheme = themeInfo;
            parentAccent = accent;
            if (!TextUtils.isEmpty(info.fileName)) {
                try {
                    File fromFile = new File(ApplicationLoader.getFilesDirFixed(), info.fileName);
                    File toFile = new File(ApplicationLoader.getFilesDirFixed(), fileName = parentTheme.generateWallpaperName(parentAccent, false));
                    AndroidUtilities.copyFile(fromFile, toFile);
                } catch (Exception e) {
                    fileName = "";
                    FileLog.e(e);
                }
            } else {
                fileName = "";
            }
            if (!TextUtils.isEmpty(info.originalFileName)) {
                if (!info.originalFileName.equals(info.fileName)){
                    try {
                        File fromFile = new File(ApplicationLoader.getFilesDirFixed(), info.originalFileName);
                        File toFile = new File(ApplicationLoader.getFilesDirFixed(), originalFileName = parentTheme.generateWallpaperName(parentAccent, true));
                        AndroidUtilities.copyFile(fromFile, toFile);
                    } catch (Exception e) {
                        originalFileName = "";
                        FileLog.e(e);
                    }
                } else {
                    originalFileName = fileName;
                }
            } else {
                originalFileName = "";
            }
        }

        public boolean isDefault() {
            return DEFAULT_BACKGROUND_SLUG.equals(slug);
        }

        public boolean isColor() {
            return COLOR_BACKGROUND_SLUG.equals(slug);
        }

        public boolean isTheme() {
            return THEME_BACKGROUND_SLUG.equals(slug);
        }

        public void saveOverrideWallpaper() {
            if (parentTheme == null || parentAccent == null && parentTheme.overrideWallpaper != this || parentAccent != null && parentAccent.overrideWallpaper != this) {
                return;
            }
            save();
        }

        private String getKey() {
            if (parentAccent != null) {
                return parentTheme.name + "_" + parentAccent.id + "_owp";
            } else {
                return parentTheme.name + "_owp";
            }
        }

        private void save() {
            try {
                String key = getKey();
                SharedPreferences themeConfig = ApplicationLoader.applicationContext.getSharedPreferences("themeconfig", Activity.MODE_PRIVATE);
                SharedPreferences.Editor editor = themeConfig.edit();
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("wall", fileName);
                jsonObject.put("owall", originalFileName);
                jsonObject.put("pColor", color);
                jsonObject.put("pGrColor", gradientColor1);
                jsonObject.put("pGrColor2", gradientColor2);
                jsonObject.put("pGrColor3", gradientColor3);
                jsonObject.put("pGrAngle", rotation);
                jsonObject.put("wallSlug", slug != null ? slug : "");
                jsonObject.put("wBlur", isBlurred);
                jsonObject.put("wMotion", isMotion);
                jsonObject.put("pIntensity", intensity);
                editor.putString(key, jsonObject.toString());
                editor.commit();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }

        private void delete() {
            String key = getKey();
            SharedPreferences themeConfig = ApplicationLoader.applicationContext.getSharedPreferences("themeconfig", Activity.MODE_PRIVATE);
            themeConfig.edit().remove(key).commit();
            new File(ApplicationLoader.getFilesDirFixed(), fileName).delete();
            new File(ApplicationLoader.getFilesDirFixed(), originalFileName).delete();
        }
    }

    public static class ThemeInfo implements NotificationCenter.NotificationCenterDelegate {
        public String name;
        public String pathToFile;
        public String pathToWallpaper;
        public String assetName;
        public String slug;
        public boolean badWallpaper;
        public boolean isBlured;
        public boolean isMotion;
        public int patternBgColor;
        public int patternBgGradientColor1;
        public int patternBgGradientColor2;
        public int patternBgGradientColor3;
        public int patternBgGradientRotation = 45;
        public int patternIntensity;

        public int account;

        public TLRPC.TL_theme info;
        public boolean loaded = true;

        public String uploadingThumb;
        public String uploadingFile;
        public TLRPC.InputFile uploadedThumb;
        public TLRPC.InputFile uploadedFile;

        private int previewBackgroundColor;
        public int previewBackgroundGradientColor1;
        public int previewBackgroundGradientColor2;
        public int previewBackgroundGradientColor3;
        public int previewWallpaperOffset;
        private int previewInColor;
        private int previewOutColor;
        public boolean firstAccentIsDefault;
        public boolean previewParsed;
        public boolean themeLoaded = true;

        public int sortIndex;

        public int defaultAccentCount;

        public int accentBaseColor;

        public int currentAccentId;
        public int prevAccentId = -1;
        public SparseArray<ThemeAccent> themeAccentsMap;
        public ArrayList<ThemeAccent> themeAccents;
        public LongSparseArray<ThemeAccent> accentsByThemeId;
        public LongSparseArray<ThemeAccent> chatAccentsByThemeId = new LongSparseArray<>();
        public int lastChatThemeId = 0;
        public int lastAccentId = 100;

        private String loadingThemeWallpaperName;
        private String newPathToWallpaper;

        public OverrideWallpaperInfo overrideWallpaper;
        private int isDark = UNKNOWN;

        private final static int DARK= 1;
        private final static int LIGHT = 0;
        private final static int UNKNOWN = -1;

        ThemeInfo() {

        }

        public ThemeInfo(ThemeInfo other) {
            this.name = other.name;
            this.pathToFile = other.pathToFile;
            this.pathToWallpaper = other.pathToWallpaper;
            this.assetName = other.assetName;
            this.slug = other.slug;
            this.badWallpaper = other.badWallpaper;
            this.isBlured = other.isBlured;
            this.isMotion = other.isMotion;
            this.patternBgColor = other.patternBgColor;
            this.patternBgGradientColor1 = other.patternBgGradientColor1;
            this.patternBgGradientColor2 = other.patternBgGradientColor2;
            this.patternBgGradientColor3 = other.patternBgGradientColor3;
            this.patternBgGradientRotation = other.patternBgGradientRotation;
            this.patternIntensity = other.patternIntensity;
            this.account = other.account;
            this.info = other.info;
            this.loaded = other.loaded;
            this.uploadingThumb = other.uploadingThumb;
            this.uploadingFile = other.uploadingFile;
            this.uploadedThumb = other.uploadedThumb;
            this.uploadedFile = other.uploadedFile;
            this.previewBackgroundColor = other.previewBackgroundColor;
            this.previewBackgroundGradientColor1 = other.previewBackgroundGradientColor1;
            this.previewBackgroundGradientColor2 = other.previewBackgroundGradientColor2;
            this.previewBackgroundGradientColor3 = other.previewBackgroundGradientColor3;
            this.previewWallpaperOffset = other.previewWallpaperOffset;
            this.previewInColor = other.previewInColor;
            this.previewOutColor = other.previewOutColor;
            this.firstAccentIsDefault = other.firstAccentIsDefault;
            this.previewParsed = other.previewParsed;
            this.themeLoaded = other.themeLoaded;
            this.sortIndex = other.sortIndex;
            this.defaultAccentCount = other.defaultAccentCount;
            this.accentBaseColor = other.accentBaseColor;
            this.currentAccentId = other.currentAccentId;
            this.prevAccentId = other.prevAccentId;
            this.themeAccentsMap = other.themeAccentsMap;
            this.themeAccents = other.themeAccents;
            this.accentsByThemeId = other.accentsByThemeId;
            this.lastAccentId = other.lastAccentId;
            this.loadingThemeWallpaperName = other.loadingThemeWallpaperName;
            this.newPathToWallpaper = other.newPathToWallpaper;
            this.overrideWallpaper = other.overrideWallpaper;
        }

        JSONObject getSaveJson() {
            try {
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("name", name);
                jsonObject.put("path", pathToFile);
                jsonObject.put("account", account);
                if (info != null) {
                    SerializedData data = new SerializedData(info.getObjectSize());
                    info.serializeToStream(data);
                    jsonObject.put("info", Utilities.bytesToHex(data.toByteArray()));
                }
                jsonObject.put("loaded", loaded);
                return jsonObject;
            } catch (Exception e) {
                FileLog.e(e);
            }
            return null;
        }

        private void loadWallpapers(SharedPreferences sharedPreferences) {
            if (themeAccents != null && !themeAccents.isEmpty()) {
                for (int a = 0, N = themeAccents.size(); a < N; a++) {
                    ThemeAccent accent = themeAccents.get(a);
                    loadOverrideWallpaper(sharedPreferences, accent, name + "_" + accent.id + "_owp");
                }
            } else {
                loadOverrideWallpaper(sharedPreferences, null, name + "_owp");
            }
        }

        private void loadOverrideWallpaper(SharedPreferences sharedPreferences, ThemeAccent accent, String key) {
            try {
                String json = sharedPreferences.getString(key,  null);
                if (TextUtils.isEmpty(json)) {
                    return;
                }
                JSONObject object = new JSONObject(json);
                OverrideWallpaperInfo wallpaperInfo = new OverrideWallpaperInfo();
                wallpaperInfo.fileName = object.getString("wall");
                wallpaperInfo.originalFileName = object.getString("owall");
                wallpaperInfo.color = object.getInt("pColor");
                wallpaperInfo.gradientColor1 = object.getInt("pGrColor");
                wallpaperInfo.gradientColor2 = object.optInt("pGrColor2");
                wallpaperInfo.gradientColor3 = object.optInt("pGrColor3");
                wallpaperInfo.rotation = object.getInt("pGrAngle");
                wallpaperInfo.slug = object.getString("wallSlug");
                wallpaperInfo.isBlurred = object.getBoolean("wBlur");
                wallpaperInfo.isMotion = object.getBoolean("wMotion");
                wallpaperInfo.intensity = (float) object.getDouble("pIntensity");
                wallpaperInfo.parentTheme = this;
                wallpaperInfo.parentAccent = accent;
                if (accent != null) {
                    accent.overrideWallpaper = wallpaperInfo;
                } else {
                    overrideWallpaper = wallpaperInfo;
                }
                if (object.has("wallId")) {
                    long id = object.getLong("wallId");
                    if (id == 1000001) {
                        wallpaperInfo.slug = DEFAULT_BACKGROUND_SLUG;
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }

        public void setOverrideWallpaper(OverrideWallpaperInfo info) {
            if (overrideWallpaper == info) {
                return;
            }
            ThemeAccent accent = getAccent(false);
            if (overrideWallpaper != null) {
                overrideWallpaper.delete();
            }
            if (info != null) {
                info.parentAccent = accent;
                info.parentTheme = this;
                info.save();
            }
            overrideWallpaper = info;
            if (accent != null) {
                accent.overrideWallpaper = info;
            }
        }

        public String getName() {
            if ("Blue".equals(name)) {
                return getString(R.string.ThemeClassic);
            } else if ("Dark Blue".equals(name)) {
                return getString(R.string.ThemeDark);
            } else if ("Arctic Blue".equals(name)) {
                return getString(R.string.ThemeArcticBlue);
            } else if ("Day".equals(name)) {
                return getString(R.string.ThemeDay);
            } else if ("Night".equals(name)) {
                return getString(R.string.ThemeNight);
            }
            return info != null ? info.title : name;
        }

        public void setCurrentAccentId(int id) {
            currentAccentId = id;
            ThemeAccent accent = getAccent(false);
            if (accent != null) {
                overrideWallpaper = accent.overrideWallpaper;
            }
        }

        public String generateWallpaperName(ThemeAccent accent, boolean original) {
            if (accent == null) {
                accent = getAccent(false);
            }
            if (accent != null) {
                return (original ? name + "_" + accent.id + "_wp_o" : name + "_" + accent.id + "_wp") + Utilities.random.nextInt() + ".jpg";
            } else {
                return (original ? name + "_wp_o" : name + "_wp") + Utilities.random.nextInt() + ".jpg";
            }
        }

        public void setPreviewInColor(int color) {
            previewInColor = color;
        }

        public void setPreviewOutColor(int color) {
            previewOutColor = color;
        }

        public void setPreviewBackgroundColor(int color) {
            previewBackgroundColor = color;
        }

        public int getPreviewInColor() {
            if (firstAccentIsDefault && currentAccentId == DEFALT_THEME_ACCENT_ID) {
                return 0xffffffff;
            }
            return previewInColor;
        }

        public int getPreviewOutColor() {
            if (firstAccentIsDefault && currentAccentId == DEFALT_THEME_ACCENT_ID) {
                return 0xfff0fee0;
            }
            return previewOutColor;
        }

        public int getPreviewBackgroundColor() {
            if (firstAccentIsDefault && currentAccentId == DEFALT_THEME_ACCENT_ID) {
                return 0xffcfd9e3;
            }
            return previewBackgroundColor;
        }


        private boolean isDefaultMyMessagesBubbles() {
            if (!firstAccentIsDefault) {
                return false;
            }
            if (currentAccentId == DEFALT_THEME_ACCENT_ID) {
                return true;
            }
            ThemeAccent defaultAccent = themeAccentsMap.get(DEFALT_THEME_ACCENT_ID);
            ThemeAccent accent = themeAccentsMap.get(currentAccentId);
            if (defaultAccent == null || accent == null) {
                return false;
            }
            return defaultAccent.myMessagesAccentColor == accent.myMessagesAccentColor &&
                    defaultAccent.myMessagesGradientAccentColor1 == accent.myMessagesGradientAccentColor1 &&
                    defaultAccent.myMessagesGradientAccentColor2 == accent.myMessagesGradientAccentColor2 &&
                    defaultAccent.myMessagesGradientAccentColor3 == accent.myMessagesGradientAccentColor3 &&
                    defaultAccent.myMessagesAnimated == accent.myMessagesAnimated;
        }

        private boolean isDefaultMyMessages() {
            if (!firstAccentIsDefault) {
                return false;
            }
            if (currentAccentId == DEFALT_THEME_ACCENT_ID) {
                return true;
            }
            ThemeAccent defaultAccent = themeAccentsMap.get(DEFALT_THEME_ACCENT_ID);
            ThemeAccent accent = themeAccentsMap.get(currentAccentId);
            if (defaultAccent == null || accent == null) {
                return false;
            }
            return defaultAccent.accentColor2 == accent.accentColor2 &&
                    defaultAccent.myMessagesAccentColor == accent.myMessagesAccentColor &&
                    defaultAccent.myMessagesGradientAccentColor1 == accent.myMessagesGradientAccentColor1 &&
                    defaultAccent.myMessagesGradientAccentColor2 == accent.myMessagesGradientAccentColor2 &&
                    defaultAccent.myMessagesGradientAccentColor3 == accent.myMessagesGradientAccentColor3 &&
                    defaultAccent.myMessagesAnimated == accent.myMessagesAnimated;
        }

        private boolean isDefaultMainAccent() {
            if (!firstAccentIsDefault) {
                return false;
            }
            if (currentAccentId == DEFALT_THEME_ACCENT_ID) {
                return true;
            }
            ThemeAccent defaultAccent = themeAccentsMap.get(DEFALT_THEME_ACCENT_ID);
            ThemeAccent accent = themeAccentsMap.get(currentAccentId);
            return accent != null && defaultAccent != null && defaultAccent.accentColor == accent.accentColor;
        }

        public boolean hasAccentColors() {
            return defaultAccentCount != 0;
        }

        public boolean isDark() {
            if (isDark != UNKNOWN) {
                return isDark == DARK;
            }
            if ("Dark Blue".equals(name) || "Night".equals(name)) {
                isDark = DARK;
            } else if ("Blue".equals(name) || "Arctic Blue".equals(name) || "Day".equals(name)) {
                isDark = LIGHT;
            }
            if (isDark == UNKNOWN) {
                String[] wallpaperLink = new String[1];
                SparseIntArray colors = getThemeFileValues(new File(pathToFile), null, wallpaperLink);
                checkIsDark(colors, this);
            }
            return isDark == DARK;
        }

        public boolean isLight() {
            return pathToFile == null && !isDark();
        }

        public String getKey() {
            if (info != null) {
                return "remote" + info.id;
            }
            return name;
        }

        static ThemeInfo createWithJson(JSONObject object) {
            if (object == null) {
                return null;
            }
            try {
                ThemeInfo themeInfo = new ThemeInfo();
                themeInfo.name = object.getString("name");
                themeInfo.pathToFile = object.getString("path");
                if (object.has("account")) {
                    themeInfo.account = object.getInt("account");
                }
                if (object.has("info")) {
                    try {
                        SerializedData serializedData = new SerializedData(Utilities.hexToBytes(object.getString("info")));
                        themeInfo.info = (TLRPC.TL_theme) TLRPC.Theme.TLdeserialize(serializedData, serializedData.readInt32(true), true);
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                }
                if (object.has("loaded")) {
                    themeInfo.loaded = object.getBoolean("loaded");
                }
                return themeInfo;
            } catch (Exception e) {
                FileLog.e(e);
            }
            return null;
        }

        static ThemeInfo createWithString(String string) {
            if (TextUtils.isEmpty(string)) {
                return null;
            }
            String[] args = string.split("\\|");
            if (args.length != 2) {
                return null;
            }
            ThemeInfo themeInfo = new ThemeInfo();
            themeInfo.name = args[0];
            themeInfo.pathToFile = args[1];
            return themeInfo;
        }

        private void setAccentColorOptions(int[] options) {
            setAccentColorOptions(options, null, null, null, null, null, null, null, null, null, null);
        }

        private void setAccentColorOptions(int[] accent, int[] myMessages, int[] myMessagesGradient, int[] background, int[] backgroundGradient1, int[] backgroundGradient2, int[] backgroundGradient3, int[] ids, String[] patternSlugs, int[] patternRotations, int[] patternIntensities) {
            defaultAccentCount = accent.length;
            themeAccents = new ArrayList<>();
            themeAccentsMap = new SparseArray<>();
            accentsByThemeId = new LongSparseArray<>();

            for (int a = 0; a < accent.length; a++) {
                ThemeAccent themeAccent = new ThemeAccent();
                themeAccent.id = ids != null ? ids[a] : a;
                if (isHome(themeAccent)) {
                    themeAccent.isDefault = true;
                }
                themeAccent.accentColor = accent[a];
                themeAccent.parentTheme = this;
                if (myMessages != null) {
                    themeAccent.myMessagesAccentColor = myMessages[a];
                }
                if (myMessagesGradient != null) {
                    themeAccent.myMessagesGradientAccentColor1 = myMessagesGradient[a];
                }
                if (background != null) {
                    themeAccent.backgroundOverrideColor = background[a];
                    if (firstAccentIsDefault && themeAccent.id == DEFALT_THEME_ACCENT_ID) {
                        themeAccent.backgroundOverrideColor = 0x100000000L;
                    } else {
                        themeAccent.backgroundOverrideColor = background[a];
                    }
                }
                if (backgroundGradient1 != null) {
                    if (firstAccentIsDefault && themeAccent.id == DEFALT_THEME_ACCENT_ID) {
                        themeAccent.backgroundGradientOverrideColor1 = 0x100000000L;
                    } else {
                        themeAccent.backgroundGradientOverrideColor1 = backgroundGradient1[a];
                    }
                }
                if (backgroundGradient2 != null) {
                    if (firstAccentIsDefault && themeAccent.id == DEFALT_THEME_ACCENT_ID) {
                        themeAccent.backgroundGradientOverrideColor2 = 0x100000000L;
                    } else {
                        themeAccent.backgroundGradientOverrideColor2 = backgroundGradient2[a];
                    }
                }
                if (backgroundGradient3 != null) {
                    if (firstAccentIsDefault && themeAccent.id == DEFALT_THEME_ACCENT_ID) {
                        themeAccent.backgroundGradientOverrideColor3 = 0x100000000L;
                    } else {
                        themeAccent.backgroundGradientOverrideColor3 = backgroundGradient3[a];
                    }
                }
                if (patternSlugs != null) {
                    themeAccent.patternIntensity = patternIntensities[a] / 100.0f;
                    themeAccent.backgroundRotation = patternRotations[a];
                    themeAccent.patternSlug = patternSlugs[a];
                }

                //override default themes
                if (isHome(themeAccent) && name.equals("Dark Blue") || name.equals("Night")) {
                    themeAccent.myMessagesAccentColor = 0xff258DE5;
                    themeAccent.myMessagesGradientAccentColor1 = 0xff4272DF;
                    themeAccent.myMessagesGradientAccentColor2 = 0xff8146D7;
                    themeAccent.myMessagesGradientAccentColor3 = 0xff9F3EAA;
                    if (name.equals("Night")) {
                        themeAccent.patternIntensity = -0.57f;
                        themeAccent.backgroundOverrideColor = 0xff6c7fa6;
                        themeAccent.backgroundGradientOverrideColor1 = 0xff2e344b;
                        themeAccent.backgroundGradientOverrideColor2 = 0xff7874a7;
                        themeAccent.backgroundGradientOverrideColor3 = 0xff333258;
                    }
                }
                themeAccentsMap.put(themeAccent.id, themeAccent);
                themeAccents.add(themeAccent);
            }
            accentBaseColor = themeAccentsMap.get(0).accentColor;
        }

        @UiThread
        private void loadThemeDocument() {
            loaded = false;
            loadingThemeWallpaperName = null;
            newPathToWallpaper = null;
            addObservers();
            FileLoader.getInstance(account).loadFile(info.document, info, FileLoader.PRIORITY_NORMAL, 1);
        }

        private void addObservers() {
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoaded);
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoadFailed);
        }


        @UiThread
        private void removeObservers() {
            NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoaded);
            NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoadFailed);
        }

        private void onFinishLoadingRemoteTheme() {
            loaded = true;
            previewParsed = false;
            saveOtherThemes(true);
            if (this == currentTheme && previousTheme == null) {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needSetDayNightTheme, this, this == currentNightTheme, null, -1, fallbackKeys);
            }
        }

        public static boolean accentEquals(ThemeAccent accent, TLRPC.ThemeSettings settings) {
            int bottomColor = settings.message_colors.size() > 0 ? settings.message_colors.get(0) | 0xff000000 : 0;
            int myMessagesGradientAccentColor1 = settings.message_colors.size() > 1 ? settings.message_colors.get(1) | 0xff000000 : 0;
            if (bottomColor == myMessagesGradientAccentColor1) {
                myMessagesGradientAccentColor1 = 0;
            }
            int myMessagesGradientAccentColor2 = settings.message_colors.size() > 2 ? settings.message_colors.get(2) | 0xff000000 : 0;
            int myMessagesGradientAccentColor3 = settings.message_colors.size() > 3 ? settings.message_colors.get(3) | 0xff000000 : 0;
            int backgroundOverrideColor = 0;
            long backgroundGradientOverrideColor1 = 0;
            long backgroundGradientOverrideColor2 = 0;
            long backgroundGradientOverrideColor3 = 0;
            int backgroundRotation = 0;
            String patternSlug = null;
            float patternIntensity = 0;
            if (settings.wallpaper != null && settings.wallpaper.settings != null) {
                backgroundOverrideColor = getWallpaperColor(settings.wallpaper.settings.background_color);
                if (settings.wallpaper.settings.second_background_color == 0) {
                    backgroundGradientOverrideColor1 = 0x100000000L;
                } else {
                    backgroundGradientOverrideColor1 = getWallpaperColor(settings.wallpaper.settings.second_background_color);
                }
                if (settings.wallpaper.settings.third_background_color == 0) {
                    backgroundGradientOverrideColor2 = 0x100000000L;
                } else {
                    backgroundGradientOverrideColor2 = getWallpaperColor(settings.wallpaper.settings.third_background_color);
                }
                if (settings.wallpaper.settings.fourth_background_color == 0) {
                    backgroundGradientOverrideColor3 = 0x100000000L;
                } else {
                    backgroundGradientOverrideColor3 = getWallpaperColor(settings.wallpaper.settings.fourth_background_color);
                }
                backgroundRotation = AndroidUtilities.getWallpaperRotation(settings.wallpaper.settings.rotation, false);
                if (!(settings.wallpaper instanceof TLRPC.TL_wallPaperNoFile) && settings.wallpaper.pattern) {
                    patternSlug = settings.wallpaper.slug;
                    patternIntensity = settings.wallpaper.settings.intensity / 100.0f;
                }
            }
            return settings.accent_color == accent.accentColor &&
                    settings.outbox_accent_color == accent.accentColor2 &&
                    bottomColor == accent.myMessagesAccentColor &&
                    myMessagesGradientAccentColor1 == accent.myMessagesGradientAccentColor1 &&
                    myMessagesGradientAccentColor2 == accent.myMessagesGradientAccentColor2 &&
                    myMessagesGradientAccentColor3 == accent.myMessagesGradientAccentColor3 &&
                    settings.message_colors_animated == accent.myMessagesAnimated &&
                    backgroundOverrideColor == accent.backgroundOverrideColor &&
                    backgroundGradientOverrideColor1 == accent.backgroundGradientOverrideColor1 &&
                    backgroundGradientOverrideColor2 == accent.backgroundGradientOverrideColor2 &&
                    backgroundGradientOverrideColor3 == accent.backgroundGradientOverrideColor3 &&
                    backgroundRotation == accent.backgroundRotation &&
                    TextUtils.equals(patternSlug, accent.patternSlug) &&
                    Math.abs(patternIntensity - accent.patternIntensity) < 0.001;
        }

        public static void fillAccentValues(ThemeAccent themeAccent, TLRPC.ThemeSettings settings) {
            themeAccent.accentColor = settings.accent_color;
            themeAccent.accentColor2 = settings.outbox_accent_color;
            themeAccent.myMessagesAccentColor = settings.message_colors.size() > 0 ? settings.message_colors.get(0) | 0xff000000 : 0;
            themeAccent.myMessagesGradientAccentColor1 = settings.message_colors.size() > 1 ? settings.message_colors.get(1) | 0xff000000 : 0;
            if (themeAccent.myMessagesAccentColor == themeAccent.myMessagesGradientAccentColor1) {
                themeAccent.myMessagesGradientAccentColor1 = 0;
            }
            themeAccent.myMessagesGradientAccentColor2 = settings.message_colors.size() > 2 ? settings.message_colors.get(2) | 0xff000000 : 0;
            themeAccent.myMessagesGradientAccentColor3 = settings.message_colors.size() > 3 ? settings.message_colors.get(3) | 0xff000000 : 0;
            themeAccent.myMessagesAnimated = settings.message_colors_animated;
            if (settings.wallpaper != null && settings.wallpaper.settings != null) {
                if (settings.wallpaper.settings.background_color == 0) {
                    themeAccent.backgroundOverrideColor = 0x100000000L;
                } else {
                    themeAccent.backgroundOverrideColor = getWallpaperColor(settings.wallpaper.settings.background_color);
                }
                if ((settings.wallpaper.settings.flags & 16) != 0 && settings.wallpaper.settings.second_background_color == 0) {
                    themeAccent.backgroundGradientOverrideColor1 = 0x100000000L;
                } else {
                    themeAccent.backgroundGradientOverrideColor1 = getWallpaperColor(settings.wallpaper.settings.second_background_color);
                }
                if ((settings.wallpaper.settings.flags & 32) != 0 && settings.wallpaper.settings.third_background_color == 0) {
                    themeAccent.backgroundGradientOverrideColor2 = 0x100000000L;
                } else {
                    themeAccent.backgroundGradientOverrideColor2 = getWallpaperColor(settings.wallpaper.settings.third_background_color);
                }
                if ((settings.wallpaper.settings.flags & 64) != 0 && settings.wallpaper.settings.fourth_background_color == 0) {
                    themeAccent.backgroundGradientOverrideColor3 = 0x100000000L;
                } else {
                    themeAccent.backgroundGradientOverrideColor3 = getWallpaperColor(settings.wallpaper.settings.fourth_background_color);
                }
                themeAccent.backgroundRotation = AndroidUtilities.getWallpaperRotation(settings.wallpaper.settings.rotation, false);
                if (!(settings.wallpaper instanceof TLRPC.TL_wallPaperNoFile) && settings.wallpaper.pattern) {
                    themeAccent.patternSlug = settings.wallpaper.slug;
                    themeAccent.patternIntensity = settings.wallpaper.settings.intensity / 100.0f;
                    themeAccent.patternMotion = settings.wallpaper.settings.motion;
                }
            }
        }

        public ThemeAccent createNewAccent(TLRPC.ThemeSettings settings) {
            ThemeAccent themeAccent = new ThemeAccent();
            fillAccentValues(themeAccent, settings);
            themeAccent.parentTheme = this;
            return themeAccent;
        }

        public ThemeAccent createNewAccent(TLRPC.TL_theme info, int account) {
            return createNewAccent(info, account, false, 0);
        }

        public ThemeAccent createNewAccent(TLRPC.TL_theme info, int account, boolean ignoreThemeInfoId, int settingsIndex) {
            if (info == null) {
                return null;
            }
            TLRPC.ThemeSettings settings = null;
            if (settingsIndex < info.settings.size()) {
                settings = info.settings.get(settingsIndex);
            }
            return createNewAccent(info.id, settings, info, account, ignoreThemeInfoId);
        }

        public ThemeAccent createNewAccent(long themeId, TLRPC.ThemeSettings settings, TLRPC.TL_theme info, int account, boolean ignoreThemeInfoId) {
            if (ignoreThemeInfoId) {
                ThemeAccent themeAccent = chatAccentsByThemeId.get(themeId);
                if (themeAccent != null) {
                    return themeAccent;
                }
                int id = ++lastChatThemeId;
                themeAccent = createNewAccent(settings);
                themeAccent.id = id;
                themeAccent.info = info;
                themeAccent.account = account;
                chatAccentsByThemeId.put(id, themeAccent);
                return themeAccent;
            } else {
                ThemeAccent themeAccent = accentsByThemeId.get(themeId);
                if (themeAccent != null) {
                    return themeAccent;
                }
                int id = ++lastAccentId;
                themeAccent = createNewAccent(settings);
                themeAccent.id = id;
                themeAccent.info = info;
                themeAccent.account = account;
                themeAccentsMap.put(id, themeAccent);
                themeAccents.add(0, themeAccent);
                sortAccents(this);
                accentsByThemeId.put(themeId, themeAccent);
                return themeAccent;
            }
        }

        public ThemeAccent getAccent(boolean createNew) {
            if (themeAccents == null) {
                return null;
            }
            ThemeAccent accent = themeAccentsMap.get(currentAccentId);
            if (accent == null) {
                return null;
            }
            if (createNew) {
                int id = ++lastAccentId;
                ThemeAccent themeAccent = new ThemeAccent();
                themeAccent.accentColor = accent.accentColor;
                themeAccent.accentColor2 = accent.accentColor2;
                themeAccent.myMessagesAccentColor = accent.myMessagesAccentColor;
                themeAccent.myMessagesGradientAccentColor1 = accent.myMessagesGradientAccentColor1;
                themeAccent.myMessagesGradientAccentColor2 = accent.myMessagesGradientAccentColor2;
                themeAccent.myMessagesGradientAccentColor3 = accent.myMessagesGradientAccentColor3;
                themeAccent.myMessagesAnimated = accent.myMessagesAnimated;
                themeAccent.backgroundOverrideColor = accent.backgroundOverrideColor;
                themeAccent.backgroundGradientOverrideColor1 = accent.backgroundGradientOverrideColor1;
                themeAccent.backgroundGradientOverrideColor2 = accent.backgroundGradientOverrideColor2;
                themeAccent.backgroundGradientOverrideColor3 = accent.backgroundGradientOverrideColor3;
                themeAccent.backgroundRotation = accent.backgroundRotation;
                themeAccent.patternSlug = accent.patternSlug;
                themeAccent.patternIntensity = accent.patternIntensity;
                themeAccent.patternMotion = accent.patternMotion;
                themeAccent.parentTheme = this;
                if (overrideWallpaper != null) {
                    themeAccent.overrideWallpaper = new OverrideWallpaperInfo(overrideWallpaper, this, themeAccent);
                }
                prevAccentId = currentAccentId;
                currentAccentId = themeAccent.id = id;
                overrideWallpaper = themeAccent.overrideWallpaper;
                themeAccentsMap.put(id, themeAccent);
                themeAccents.add(0, themeAccent);
                sortAccents(this);
                return themeAccent;
            } else {
                return accent;
            }
        }

        public int getAccentColor(int id) {
            ThemeAccent accent = themeAccentsMap.get(id);
            return accent != null ? accent.accentColor : 0;
        }

        public boolean createBackground(File file, String toPath) {
            try {
                Bitmap bitmap = AndroidUtilities.getScaledBitmap(dp(640), dp(360), file.getAbsolutePath(), null, 0);
                if (bitmap != null && patternBgColor != 0) {
                    Bitmap finalBitmap = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), bitmap.getConfig());
                    Canvas canvas = new Canvas(finalBitmap);
                    int patternColor;
                    if (patternBgGradientColor2 != 0) {
                        patternColor = MotionBackgroundDrawable.getPatternColor(patternBgColor, patternBgGradientColor1, patternBgGradientColor2, patternBgGradientColor3);
                    } else if (patternBgGradientColor1 != 0) {
                        patternColor = AndroidUtilities.getAverageColor(patternBgColor, patternBgGradientColor1);
                        GradientDrawable gradientDrawable = new GradientDrawable(BackgroundGradientDrawable.getGradientOrientation(patternBgGradientRotation), new int[]{patternBgColor, patternBgGradientColor1});
                        gradientDrawable.setBounds(0, 0, finalBitmap.getWidth(), finalBitmap.getHeight());
                        gradientDrawable.draw(canvas);
                    } else {
                        patternColor = AndroidUtilities.getPatternColor(patternBgColor);
                        canvas.drawColor(patternBgColor);
                    }
                    Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
                    paint.setColorFilter(new PorterDuffColorFilter(patternColor, PorterDuff.Mode.SRC_IN));
                    paint.setAlp
