# Native symbols in milkdrop_jni.cpp use this class and its method names.
-keep class com.milkdrop.visualizer.render.ProjectMBridge { *; }

# Cast loads the OptionsProvider named in AndroidManifest.xml through reflection.
-keep class com.milkdrop.visualizer.cast.MilkDropCastOptions { *; }
