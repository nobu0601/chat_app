# AccessibilityService はシステムから名前でバインドされるため難読化しない
-keep class io.github.nobu0601.icocaautocharge.accessibility.IcocaAccessibilityService { *; }

# WorkManager の Worker はリフレクションで生成される
-keep class * extends androidx.work.ListenableWorker { public <init>(...); }

# Room が生成する実装
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# 列挙値は状態の永続化に valueOf/name を使うため保持する
-keepclassmembers enum io.github.nobu0601.icocaautocharge.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
