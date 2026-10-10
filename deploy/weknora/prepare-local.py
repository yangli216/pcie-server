#!/usr/bin/env python3
"""Create local-only WeKnora configuration without exposing or rotating secrets."""
import os
from pathlib import Path
import secrets


def prepare(directory: Path) -> Path:
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    path = directory / 'runtime.env'
    values = {
        'WEKNORA_VERSION': 'v0.8.2',
        'WEKNORA_UI_PORT': '8091',
        'WEKNORA_API_PORT': '8092',
        'TZ': 'Asia/Shanghai',
        'WEKNORA_LANGUAGE': 'zh-CN',
        'LOG_LEVEL': 'info',
        'GIN_MODE': 'release',
        'LLM_DEBUG_LOG': 'false',
        'AUTO_MIGRATE': 'true',
        'DB_DRIVER': 'postgres',
        'DB_HOST': 'postgres',
        'DB_PORT': '5432',
        'DB_USER': 'weknora',
        'DB_NAME': 'weknora',
        'DB_PASSWORD': secrets.token_hex(24),
        'RETRIEVE_DRIVER': 'postgres',
        'REDIS_ADDR': 'redis:6379',
        'REDIS_PASSWORD': secrets.token_hex(24),
        'REDIS_DB': '0',
        'DOCREADER_ADDR': 'docreader:50051',
        'DOCREADER_TRANSPORT': 'grpc',
        'STORAGE_TYPE': 'local',
        'LOCAL_STORAGE_BASE_DIR': '/data/files',
        'FRONTEND_BASE_URL': 'http://127.0.0.1:8091',
        'OLLAMA_BASE_URL': 'http://host.docker.internal:11434',
        'OLLAMA_OPTIONAL': 'true',
        'LANGFUSE_ENABLED': 'false',
        'NEO4J_ENABLE': 'false',
        'WEKNORA_SANDBOX_DOCKER_ENABLED': 'false',
        'WEKNORA_TENANT_ENABLE_CROSS_TENANT_ACCESS': 'false',
        'WEKNORA_TENANT_ENABLE_RBAC': 'true',
        'DISABLE_REGISTRATION': 'false',
        'JWT_SECRET': secrets.token_hex(32),
        # WeKnora requires exactly 32 ASCII bytes, not a hex string of 64 bytes.
        'SYSTEM_AES_KEY': secrets.token_hex(16),
        'CONCURRENCY_POOL_SIZE': '5',
        'MAX_FILE_SIZE_MB': '50',
    }
    try:
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        print('Existing local configuration preserved:', path)
        return path
    with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
        stream.write('# Local development only. Do not commit or share this file.\n')
        stream.write('\n'.join(f'{key}={value}' for key, value in values.items()) + '\n')
    print('Created private local configuration:', path)
    return path


if __name__ == '__main__':
    prepare(Path(__file__).resolve().parent / '.local')
