from pathlib import Path

root = Path(__file__).resolve().parents[1]
main_path = root / "app/src/main/java/com/hoststorm/livestorm/MainActivity.kt"
overlay_path = root / "app/src/main/java/com/hoststorm/livestorm/OverlayController.kt"

main = main_path.read_text(encoding="utf-8")
broken = 'assinatura.\n\n" +'
fixed = 'assinatura.\\n\\n" +'
if broken not in main:
    raise RuntimeError("Trecho do aviso OAuth não encontrado para correção")
main_path.write_text(main.replace(broken, fixed, 1), encoding="utf-8")

overlay = overlay_path.read_text(encoding="utf-8")n