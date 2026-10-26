import os
import uvicorn

if __name__ == "__main__":
    uvicorn.run("scorer_service.service:app", host="0.0.0.0", port=int(os.getenv("SCORER_PORT", "8000")))
