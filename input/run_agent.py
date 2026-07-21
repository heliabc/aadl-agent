import os
import sys
import json
from dotenv import load_dotenv

os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from agent import RequirementAnalysisAgent


def main():
    load_dotenv()
    
    api_key = os.getenv("DEEPSEEK_API_KEY")
    if not api_key:
        print("请设置 DEEPSEEK_API_KEY 环境变量或在 .env 文件中配置")
        print("示例：复制 .env.example 为 .env 并填写 API Key")
        return
    
    agent = RequirementAnalysisAgent(api_key=api_key, model="deepseek-v4-flash", use_rag=True)
    
    agent.set_project_info(
        project_name="作战指挥系统",
        system_name="综合指挥信息系统",
    )
    
    print("初始化知识库...")
    agent.init_knowledge_base()
    
    input_file = "input/sample_requirement.md"
    if not os.path.exists(input_file):
        print(f"错误：输入文件不存在: {input_file}")
        return
    
    with open(input_file, "r", encoding="utf-8") as f:
        requirement_doc = f.read()
    
    print("=" * 60)
    print("军工需求分析智能体（条目化需求提取）")
    print("=" * 60)
    print(f"\n项目名称: {agent.project_name}")
    print(f"系统名称: {agent.system_name}")
    print(f"文档编号: {agent.document_id}")
    print(f"RAG启用: {agent.use_rag}")
    print(f"输入文件: {input_file}")
    print("\n" + "=" * 60)
    
    try:
        requirements = agent.process_requirement_doc(requirement_doc)
        
        print("\n" + "=" * 60)
        print("提取的条目化需求:")
        print("=" * 60)
        print()
        
        if requirements:
            print(agent.format_requirements_table(requirements))
            print()
            
            print("详细需求列表:")
            print("-" * 60)
            for i, req in enumerate(requirements, 1):
                print(f"\n[{i}] {req.get('需求ID', '')} - {req.get('需求标题', '')}")
                print(f"   优先级: {req.get('优先级', '')}")
                print(f"   描述: {req.get('需求描述', '')}")
                print(f"   验收标准:")
                for j, standard in enumerate(req.get('验收标准', '').split('\n'), 1):
                    if standard.strip():
                        print(f"     {j}. {standard.strip()}")
                if req.get('相关依赖'):
                    print(f"   依赖: {req.get('相关依赖', '')}")
            
            output_dir = "output"
            os.makedirs(output_dir, exist_ok=True)
            
            timestamp = os.path.basename(input_file).replace('.md', '')
            json_path = os.path.join(output_dir, f"{timestamp}_requirements.json")
            with open(json_path, "w", encoding="utf-8") as f:
                json.dump(requirements, f, ensure_ascii=False, indent=2)
            
            md_path = os.path.join(output_dir, f"{timestamp}_requirements.md")
            with open(md_path, "w", encoding="utf-8") as f:
                f.write("# 条目化需求列表\n\n")
                f.write("## 需求总览\n\n")
                f.write("| 需求ID | 需求标题 | 优先级 |\n")
                f.write("|--------|----------|--------|\n")
                for req in requirements:
                    f.write(f"| {req.get('需求ID', '')} | {req.get('需求标题', '')} | {req.get('优先级', '')} |\n")
                
                f.write("\n## 详细需求\n\n")
                for req in requirements:
                    f.write(f"### {req.get('需求ID', '')} {req.get('需求标题', '')}\n\n")
                    f.write(f"**优先级**: {req.get('优先级', '')}\n\n")
                    f.write(f"**需求描述**:\n{req.get('需求描述', '')}\n\n")
                    f.write(f"**验收标准**:\n")
                    for j, standard in enumerate(req.get('验收标准', '').split('\n'), 1):
                        if standard.strip():
                            f.write(f"{j}. {standard.strip()}\n")
                    f.write("\n")
                    if req.get('相关依赖'):
                        f.write(f"**相关依赖**: {req.get('相关依赖', '')}\n\n")
            
            print(f"\n输出文件已保存:")
            print(f"  - JSON: {json_path}")
            print(f"  - Markdown: {md_path}")
        else:
            print("未提取到需求")
        
    except Exception as e:
        print(f"\n错误: {str(e)}")
        import traceback
        traceback.print_exc()


if __name__ == "__main__":
    main()
