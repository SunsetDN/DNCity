import os
import subprocess
from pathlib import Path

# 모드 폴더들이 모여 있는 최상위 디렉토리 경로 지정
MODS_DIR = Path("./mods")

def run_git_push_only(base_dir: Path):
    if not base_dir.exists() or not base_dir.is_dir():
        print(f"❌ 디렉토리를 찾을 수 없습니다: {base_dir.resolve()}")
        return

    for folder in base_dir.iterdir():
        if folder.is_dir() and (folder / ".git").exists():
            print(f"\n🚀 [{folder.name}] Push 시도 중...")
            try:
                result = subprocess.run(
                    ["git", "push"],
                    cwd=folder,
                    check=True,
                    capture_output=True,
                    text=True,
                )
                print(f"🎉 [{folder.name}] Push 완료!")
                if result.stdout.strip():
                    print(result.stdout.strip())

            except subprocess.CalledProcessError as e:
                print(f"❌ [{folder.name}] Push 실패:")
                print(e.stderr if e.stderr else e.stdout)
        elif folder.is_dir():
            print(f"⏭️ [{folder.name}] Git 리포지토리가 아니므로 건너뜁니다.")

if __name__ == "__main__":
    run_git_push_only(MODS_DIR)