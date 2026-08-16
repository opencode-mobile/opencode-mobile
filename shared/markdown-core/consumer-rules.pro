# Joni's QuantifierNode nested ReduceType enum and REDUCE_TABLE static
# initializer must stay intact. R8 rewriting that <clinit> NPEs on the first
# TextMate pattern with *, +, or ? and crashes highlighting
# (ExceptionInInitializerError).
-keep class org.joni.** { *; }
-keep class org.jcodings.** { *; }
-dontwarn org.joni.**
-dontwarn org.jcodings.**
