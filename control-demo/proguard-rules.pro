# LiteRT-LM's JNI implementation looks up Kotlin binding methods by their
# original names. The published AAR does not include consumer keep rules, so
# R8 can remove those methods and make nativeCreateConversation abort with
# "JNI DETECTED ERROR IN APPLICATION: mid == null" in minified builds.
-keep class com.google.ai.edge.litertlm.** { *; }
