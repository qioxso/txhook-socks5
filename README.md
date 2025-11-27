

配合surfing或者box4或者 https://github.com/boxproxy/box 使用

mihomo里的config.yaml添加：
proxies:
  - name: 🖥️ 电脑抓包代理
    type: socks5 
    server: 192.168.43.150  #这里改为自己的
    port: 8000
proxy-groups:  
  - name: 抓包代理组
    type: select
    proxies:
      - 🖥️ 电脑抓包代理
      - DIRECT
rules:
  - PROCESS-NAME,com.tencent.mobileqq,抓包代理组
  - DOMAIN-SUFFIX,qq.com,抓包代理组
