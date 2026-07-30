from pathlib import Path

root = Path(__file__).resolve().parents[1]
api_path = root / "app/src/main/java/com/hoststorm/livestorm/YoutubeLiveApi.kt"
gradle_path = root / "app/build.gradle.kts"

api = api_path.read_text(encoding="utf-8")
old = '''            query = mapOf(
                "part" to "id,snippet,status,contentDetails",
                "broadcastStatus" to "all",
                "mine" to "true",
                "maxResults" to "50"
            )'''
new = '''            query = mapOf(
                "part" to "id,snippet,status,contentDetails",
                // A API aceita apenas um filtro principal por chamada.
                // mine=true já limita a resposta às transmissões da conta autorizada.
                "mine" to "true",
                "maxResults" to "50"
            )'''
if old not in api:
    raise SystemExit("Bloco da consulta liveBroadcasts.list não encontrado")
api_path.write_text(api.replace(old, new, 1), encoding="utf-8")

gradle = gradle_path.read_text(encoding="utf-8")
gradle = gradle.replace('versionCode = 7', 'versionCode = 8', 1)
gradle = gradle.replace('versionName = "0.6.0"', 'versionName = "0.6.1"', 1)
gradle_path.write_text(gradle, encoding="utf-8")
