"""
云端 SSH 工具：连接算家云服务器，执行命令、传输文件
"""
import paramiko
import time
import os
from pathlib import Path

HOST = "xn-a.suanjiayun.com"
PORT = 2020
USER = "15571557482"
PASS = "hl752516"


def connect_shell():
    """连接到算家云并进入 shell"""
    sock = paramiko.Transport((HOST, PORT))
    sock.banner_timeout = 30
    sock.connect(username=USER, password=PASS)
    
    chan = sock.open_session()
    chan.get_pty(term="vt100", width=120, height=50)
    chan.invoke_shell()
    chan.settimeout(15)
    
    def _read(wait=1.5):
        time.sleep(wait)
        out = b""
        while chan.recv_ready():
            out += chan.recv(32768)
        return out.decode("utf-8", errors="replace")
    
    # 初始菜单
    _read(2)
    # 选 0
    chan.send("0\n")
    _read(2)
    # 回车进入
    chan.send("\n")
    _read(3)
    
    return sock, chan, _read


def run_command(chan, read_fn, cmd, wait=3):
    """在 shell 中执行命令并返回输出"""
    chan.send(cmd + "\n")
    return read_fn(wait)


import re

def strip_ansi(text):
    """去除 ANSI 转义序列"""
    ansi_escape = re.compile(r'\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])')
    return ansi_escape.sub('', text)


def strip_echo(output, cmd):
    """去掉命令回显和提示符，只保留输出"""
    output = strip_ansi(output)
    lines = output.split("\n")
    # 去掉命令回显行
    if lines and cmd.strip() in lines[0]:
        lines = lines[1:]
    # 去掉最后一行提示符
    if lines and (lines[-1].strip().endswith("#") or lines[-1].strip().endswith("$")):
        lines = lines[:-1]
    return "\n".join(lines).strip()


def sftp_connect():
    """通过 SFTP 传输文件（如果服务器支持的话）"""
    transport = paramiko.Transport((HOST, PORT))
    transport.banner_timeout = 30
    transport.connect(username=USER, password=PASS)
    sftp = paramiko.SFTPClient.from_transport(transport)
    return transport, sftp


if __name__ == "__main__":
    sock, chan, read_fn = connect_shell()
    
    # 测试命令
    out = run_command(chan, read_fn, "echo CONNECTED && whoami && pwd")
    print(strip_echo(out, "echo CONNECTED && whoami && pwd"))
    
    chan.close()
    sock.close()
