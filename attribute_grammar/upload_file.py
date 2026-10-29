"""
文件上传工具：通过 shell + base64 上传文件到云端
（ssh2js 服务器不支持 SFTP，用 shell 通道传输）
"""
import base64
from cloud_ssh import connect_shell, run_command, strip_echo


def upload_file(chan, read_fn, local_path, remote_path, chunk_size=2048):
    """上传单个文件，分块 base64 传输"""
    with open(local_path, "rb") as f:
        data = f.read()
    
    b64 = base64.b64encode(data).decode("ascii")
    
    # 先清空目标文件
    run_command(chan, read_fn, f"> {remote_path}")
    
    # 分块写入
    for i in range(0, len(b64), chunk_size):
        chunk = b64[i:i+chunk_size]
        run_command(chan, read_fn, f'echo -n "{chunk}" >> {remote_path}.b64', wait=0.5)
    
    # 解码
    out = run_command(chan, read_fn, f"base64 -d {remote_path}.b64 > {remote_path} && rm {remote_path}.b64 && wc -c {remote_path}")
    
    # 验证大小
    result = strip_echo(out, f"base64 -d {remote_path}.b64 > {remote_path} && rm {remote_path}.b64 && wc -c {remote_path}")
    actual_size = int(result.strip().split()[0]) if result.strip() else 0
    expected_size = len(data)
    
    return actual_size == expected_size, actual_size, expected_size


if __name__ == "__main__":
    import sys
    
    if len(sys.argv) < 3:
        print("用法: python upload_file.py <本地路径> <远端路径>")
        sys.exit(1)
    
    local = sys.argv[1]
    remote = sys.argv[2]
    
    sock, chan, read_fn = connect_shell()
    
    ok, actual, expected = upload_file(chan, read_fn, local, remote)
    if ok:
        print(f"✅ 上传成功: {local} -> {remote} ({actual} bytes)")
    else:
        print(f"❌ 上传失败: 期望 {expected} bytes，实际 {actual} bytes")
    
    chan.close()
    sock.close()
