import os
from typing import Any, Dict, List, Optional
from dotenv import load_dotenv
from langfuse import Langfuse
from openai.types.chat import ChatCompletion, ChatCompletionMessage, ChatCompletionMessageParam

class LangfuseService:
    def __init__(self):
        load_dotenv()  # Wczytaj dane z pliku .env
        self.langfuse = Langfuse(
            secret_key=os.getenv("LANGFUSE_SECRET_KEY"),
            public_key=os.getenv("LANGFUSE_PUBLIC_KEY"),
            host=os.getenv("LANGFUSE_HOST")
        )

        # Debug mode in development
        if os.getenv("ENVIRONMENT") == "development":
            self.langfuse.debug()

    def create_trace(self, id: str, name: str, session_id: str):
        return self.langfuse.trace(
            id=id,
            name=name,
            session_id=session_id
        )

    def create_span(self, trace, name: str, input: Any = None):
        return trace.span(
            name=name,
            input=str(input) if input is not None else None
        )

    def finalize_span(self, 
                     span, 
                     name: str, 
                     input: List[Dict], 
                     output: ChatCompletion) -> None:
        span.update(
            name=name,
            output=str(output.choices[0].message)
        )

        span.generation(
            name=name,
            model=output.model,
            model_parameters={
                "temperature": 0.3
            },
            input=input,
            output=output,
            # output=str(output.choices[0].message.content),
            usage={
                "prompt_tokens": output.usage.prompt_tokens if output.usage else None,
                "completion_tokens": output.usage.completion_tokens if output.usage else None,
                "total_tokens": output.usage.total_tokens if output.usage else None,
            }
        ).end()
        span.end()

    def finalize_trace(self, 
                      trace, 
                      original_messages: List[Dict], 
                      generated_messages: List[Dict]) -> None:
        input_messages = [msg for msg in original_messages if msg["role"] != "system"]
        trace.update(
            input=str(input_messages),
            output=str(generated_messages)
        )
        self.langfuse.flush()

    def shutdown(self) -> None:
        self.langfuse.shutdown() 

    def get_system_prompt(self, prompt_name: str) -> str:
        """Fetch a prompt from Langfuse by its name."""
        try:
            print(f"Attempting to fetch prompt '{prompt_name}' from Langfuse...")
            prompt = self.langfuse.get_prompt(prompt_name)
            if not prompt:
                print(f"Prompt '{prompt_name}' not found in Langfuse")
                return None
            print(f"Successfully fetched prompt '{prompt_name}'")
            return prompt.prompt
        except Exception as e:
            print(f"Error fetching prompt from Langfuse: {str(e)}")
            print(f"Error type: {type(e)}")
            import traceback
            print(f"Traceback: {traceback.format_exc()}")
            return None 