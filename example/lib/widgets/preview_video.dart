import 'dart:io';

import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:video_player/video_player.dart';

class PreviewView extends StatefulWidget {
  final String path;
  const PreviewView({super.key, required this.path});

  @override
  State<PreviewView> createState() => _PreviewViewState();
}

class _PreviewViewState extends State<PreviewView> {
  VideoPlayerController? _controller;
  bool _isInitialized = false;

  Future<String> getCachePath() async {
    final cacheDir = await getExternalStorageDirectory();
    return '${cacheDir!.path}/result.mp4';
  }

  Future<void> initialize() async {
    try {
      // final path = await getCachePath();
      debugPrint('Video path: ${widget.path}');
      final file = File(widget.path);

      if (!await file.exists()) {
        debugPrint('Video file not found at: ${widget.path}');
        return;
      }

      _controller = VideoPlayerController.file(file);
      await _controller!.initialize();

      if (mounted) {
        setState(() {
          _isInitialized = true;
        });
      }
    } catch (e) {
      debugPrint('Error initializing video player: $e');
    }
  }

  @override
  void initState() {
    super.initState();
    initialize();
  }

  @override
  void dispose() {
    _controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Play Video'),
      ),
      body: Center(
        child: _isInitialized && _controller != null
            ? AspectRatio(
                aspectRatio: _controller!.value.aspectRatio,
                child: VideoPlayer(_controller!),
              )
            : const Center(
                child: CircularProgressIndicator(),
              ),
      ),
      floatingActionButton: _isInitialized && _controller != null
          ? FloatingActionButton(
              onPressed: () {
                setState(() {
                  if (_controller!.value.isPlaying) {
                    _controller!.pause();
                  } else {
                    _controller!.play();
                  }
                });
              },
              child: Icon(
                _controller!.value.isPlaying ? Icons.pause : Icons.play_arrow,
              ),
            )
          : null,
    );
  }
}
