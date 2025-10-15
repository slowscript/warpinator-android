# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-dontobfuscate

# Netty
-keep public class io.netty.util.ReferenceCountUtil {
    *;
}
-keep public interface io.netty.channel.ChannelOutboundInvoker {
    *;
}
-keep class io.netty.handler.ssl.SslHandler { *; }
-keep class io.netty.handler.codec.http2.* { *; }
-keep class io.netty.channel.* { *; }
-keep class io.grpc.netty.ProtocolNegotiators$GrpcNegotiationHandler { *; }
-keep class io.grpc.netty.NettyServerHandler { *; }
-keep class io.grpc.netty.ProtocolNegotiators$ServerTlsHandler { *; }
-keep class io.grpc.netty.ProtocolNegotiators$WaitUntilActiveHandler { *; }
-keep class io.grpc.netty.WriteBufferingAndExceptionHandler { *; }

# related to netty:
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.jcraft.jzlib.*

# These should not be needed
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
-dontwarn reactor.blockhound.integration.BlockHoundIntegration
-dontwarn io.netty.internal.tcnative.*
-dontwarn org.jetbrains.annotations.*
# ??
-dontwarn org.apache.log4j.Level
-dontwarn org.apache.log4j.Logger
-dontwarn org.apache.log4j.Priority
-dontwarn org.apache.logging.log4j.Level
-dontwarn org.apache.logging.log4j.LogManager
-dontwarn org.apache.logging.log4j.Logger
-dontwarn org.apache.logging.log4j.message.MessageFactory
-dontwarn org.apache.logging.log4j.spi.ExtendedLogger
-dontwarn org.apache.logging.log4j.spi.ExtendedLoggerWrapper
-dontwarn org.eclipse.jetty.alpn.ALPN$ClientProvider
-dontwarn org.eclipse.jetty.alpn.ALPN$Provider
-dontwarn org.eclipse.jetty.alpn.ALPN$ServerProvider
-dontwarn org.eclipse.jetty.alpn.ALPN
-dontwarn org.eclipse.jetty.npn.NextProtoNego$ClientProvider
-dontwarn org.eclipse.jetty.npn.NextProtoNego$Provider
-dontwarn org.eclipse.jetty.npn.NextProtoNego$ServerProvider
-dontwarn org.eclipse.jetty.npn.NextProtoNego