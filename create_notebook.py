import json

with open("colab_setup.py", "r", encoding="utf-8") as f:
    setup_code = f.read()

notebook_content = {
    "cells": [
        {
            "cell_type": "markdown",
            "metadata": {},
            "source": [
                "# 🤖 AndroidEmulator - Dual Android Emulator ADB Microservice\n",
                "\n",
                "This notebook automatically sets up Java 17, Android SDK API 30, creates 2 Android Virtual Devices (AVDs) with 2GB storage partitions, launches a FastAPI microservice on port 8000, and connects to Cloudflare Tunnel at `https://ollama.kushalneedsmcp.online`."
            ]
        },
        {
            "cell_type": "code",
            "execution_count": None,
            "metadata": {},
            "outputs": [],
            "source": [
                setup_code
            ]
        }
    ],
    "metadata": {
        "colab": {
            "name": "AndroidEmulator.ipynb",
            "provenance": []
        },
        "language_info": {
            "name": "python"
        }
    },
    "nbformat": 4,
    "nbformat_minor": 0
}

with open("AndroidEmulator.ipynb", "w", encoding="utf-8") as f:
    json.dump(notebook_content, f, indent=2)

print("AndroidEmulator.ipynb updated successfully!")
