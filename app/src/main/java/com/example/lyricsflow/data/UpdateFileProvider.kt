package com.example.lyricsflow.data

import androidx.core.content.FileProvider

/** Dedicated provider avoids vendor issues with instantiating the AndroidX base class directly. */
class UpdateFileProvider : FileProvider()
