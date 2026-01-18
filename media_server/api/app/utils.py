"""Utility functions for the application."""

import random
import string


def generate_unique_code(length: int = 6) -> str:
    """
    Generate a random unique code with alphanumeric characters.

    Args:
        length: Length of the code (default: 6)

    Returns:
        A random string of specified length containing uppercase letters and digits
    """
    characters = string.ascii_uppercase + string.digits
    return ''.join(random.choice(characters) for _ in range(length))
