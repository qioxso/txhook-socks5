import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;

public class Socks5ProxyServer {
    private final int port;

    public Socks5ProxyServer(int port) {
        this.port = port;
    }

    public void run() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) {
                            // SOCKS5 协议处理流水线
                            // 1. 负责将服务器响应编码为 SOCKS5 格式
                            ch.pipeline().addLast(Socks5ServerEncoder.DEFAULT);
                            // 2. 负责解码客户端的初始化请求 (握手)
                            ch.pipeline().addLast(new Socks5InitialRequestDecoder());
                            // 3. 自定义处理逻辑
                            ch.pipeline().addLast(new Socks5Handler());
                        }
                    });

            System.out.println("SOCKS5 Proxy Server started on port " + port);
            ChannelFuture f = b.bind(port).sync();
            f.channel().closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    public static void main(String[] args) throws Exception {
        new Socks5ProxyServer(8000).run();
    }
}
