from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    APP_NAME: str = "Calendar FastAPI Backend"
    APP_HOST: str = "0.0.0.0"
    APP_PORT: int = 8000
    APP_RELOAD: bool = False

    MODEL_PATH: str = "./models/qwen_gguf/Qwen3.5-4B-Q4_K_M.gguf"
    N_CTX: int = 4096
    MAX_TOKENS: int = 512
    TEMPERATURE: float = 0.7
    TOP_P: float = 0.9

    REMOTE_LLM_BASE_URL: str = ""
    REMOTE_LLM_CHAT_PATH: str = "/v1/chat/completions"
    REMOTE_LLM_API_KEY: str = ""
    REMOTE_LLM_MODEL: str = ""
    REMOTE_LLM_TIMEOUT_SECONDS: float = 120.0

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
    )


settings = Settings()
