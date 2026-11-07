# ============================================================
# Cryptotrader — ProGuard/R8 rules
# ============================================================

# Сохраняем информацию о строках для краш-логов
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Сохраняем аннотации и generic-сигнатуры (нужно Firebase, OkHttp, Kotlin)
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# ============================================================
# Firebase Analytics
# ============================================================
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# Firebase использует рефлексию для инициализации компонентов
-keep class com.google.firebase.components.** { *; }
-keep class com.google.firebase.analytics.** { *; }
-keep class com.google.firebase.messaging.** { *; }

# ============================================================
# OkHttp / Okio
# ============================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# OkHttp использует ServiceLoader для платформенных реализаций
-keep class okhttp3.internal.platform.** { *; }
-keep class okhttp3.internal.publicsuffix.** { *; }

# ============================================================
# Kotlin
# ============================================================
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Data-классы: сохраняем copy(), componentN(), toString()
-keepclassmembers class ** {
    *** copy(...);
    *** component*();
}

# ============================================================
# Твои data-классы (JSON-сериализация)
# ============================================================
# Candle, Signal, Trade, LsPoint, SpeedReading, SignalResult,
# PeakForecast, ScanResult, LiqEvent, LiqStats, OrderbookInfo,
# AppState, PaperTrade, SymbolSignal, SignalMarker, BacktestSignal,
# BacktestReport, TradeDeltaAnalyzer, ContextSnapshot, Stats,
# GroupStats, PatternGroup, ExitReasonStats, HorizonStats, PatternStats
-keep class com.cryptotrader.bybit.** { *; }

# ============================================================
# JSON / org.json (системный, но на всякий)
# ============================================================
-keep class org.json.** { *; }

# ============================================================
# Отключаем предупреждения для необязательных зависимостей
# ============================================================
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn kotlinx.**