# Voz IA Android — prototipo 0.2.0

Aplicación Android para crear un perfil de voz autorizado y conversar con una IA que reproduce sus respuestas usando ese perfil.

## Incluye
- Grabación de muestra desde el micrófono.
- Confirmación explícita de autorización de la voz.
- Creación de perfil de voz mediante un backend.
- Conversación por texto o dictado.
- Modo manos libres: escucha, responde y vuelve a escuchar.
- GitHub Actions para generar el APK debug.

## Arquitectura
Android -> Backend FastAPI -> OpenAI Responses API -> proveedor de síntesis/clonación de voz.

Las claves de API se mantienen en el servidor y no dentro del APK.

## APK
El workflow de GitHub Actions genera el artefacto `VozIA-debug-apk`.

## Privacidad
Usa únicamente una voz propia o una voz para la cual exista permiso explícito.
