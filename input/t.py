import csv
import os
import re

# CSV路径
csv_path = r"C:\Users\heli0\Desktop\IMA系统需求分析数据集\data1.csv"
start_row_idx = 3
# 正则匹配：xxx系统+数字，提取文件名核心
pattern = re.compile(r"([^，。：\n\r]+系统\d+)")

# 兼容Windows CSV编码
def read_csv(path):
    for enc in ["gbk", "gb2312", "utf-8"]:
        try:
            with open(path, "r", encoding=enc, newline="") as f:
                return list(csv.reader(f))
        except UnicodeDecodeError:
            continue
    raise Exception("CSV文件编码无法识别")

if __name__ == "__main__":
    all_rows = read_csv(csv_path)
    target_rows = all_rows[start_row_idx:]

    for row in target_rows:
        if len(row) < 2:
            continue
        text_content = row[1].strip()
        # 匹配系统名称
        match_res = pattern.search(text_content)
        if not match_res:
            print(f"该行未匹配到系统名，跳过：{text_content[:30]}...")
            continue
        sys_name = match_res.group(1)
        file_name = f"{sys_name}.txt"
        # 写入文件
        with open(file_name, "w", encoding="utf-8") as f_out:
            f_out.write(text_content)
        print(f"生成文件：{file_name}")