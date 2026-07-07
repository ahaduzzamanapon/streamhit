import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:provider/provider.dart';
import 'package:video_player/video_player.dart';
import '../core/api_service.dart';
import '../core/constants.dart';
import '../models/subject.dart';
import '../providers/history_provider.dart';

class WatchScreen extends StatefulWidget {
  final int subjectId;
  final String title;
  final String detailPath;
  final String? coverUrl;
  final int season;
  final int episode;
  final String? directUrl; // For live TV / sports streams

  const WatchScreen({
    super.key,
    required this.subjectId,
    required this.title,
    required this.detailPath,
    this.coverUrl,
    this.season = 0,
    this.episode = 0,
    this.directUrl,
  });

  @override
  State<WatchScreen> createState() => _WatchScreenState();
}

class _WatchScreenState extends State<WatchScreen> {
  // Player
  VideoPlayerController? _controller;
  List<PlayResource> _resources = [];
  PlayResource? _current;

  // State flags
  bool _loadingResources = true;
  bool _initializingPlayer = false;
  bool _hasError = false;
  String _errorMsg = '';

  // Controls UI
  bool _showControls = true;
  bool _isFullscreen = false;
  Timer? _hideTimer;
  Timer? _progressTimer;

  // Seek feedback
  bool _seekLeft = false;
  bool _seekRight = false;

  @override
  void initState() {
    super.initState();
    _loadResources();
  }

  @override
  void dispose() {
    _hideTimer?.cancel();
    _progressTimer?.cancel();
    _controller?.dispose();
    // Always restore portrait on exit
    SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    super.dispose();
  }

  // ── Resource Loading ─────────────────────────────────────────────────────

  Future<void> _loadResources() async {
    if (!mounted) return;
    setState(() {
      _loadingResources = true;
      _hasError = false;
      _errorMsg = '';
    });

    // Live TV / sports: play direct URL without API call
    if (widget.directUrl != null && widget.directUrl!.isNotEmpty) {
      _resources = [
        PlayResource(
            resourceId: 'live',
            resourceLink: widget.directUrl!,
            resolution: 0,
            language: 'Live')
      ];
      _current = _resources.first;
      setState(() => _loadingResources = false);
      await _initPlayer(_current!.resourceLink);
      return;
    }

    try {
      final resources = await ApiService.fetchPlayResources(
        widget.subjectId,
        detailPath: widget.detailPath,
        se: widget.season > 0 ? widget.season : null,
        ep: widget.episode > 0 ? widget.episode : null,
      );

      if (!mounted) return;

      if (resources.isEmpty) {
        setState(() {
          _loadingResources = false;
          _hasError = true;
          _errorMsg =
              'No stream available for this content.\nPlease try again later or check another episode.';
        });
        return;
      }

      _resources = resources;
      // Pick highest resolution as default
      _current = resources.reduce(
          (a, b) => a.resolution >= b.resolution ? a : b);

      setState(() => _loadingResources = false);
      await _initPlayer(_current!.resourceLink);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loadingResources = false;
        _hasError = true;
        _errorMsg =
            'Connection error. Please check your internet and try again.';
      });
    }
  }

  // ── Player Initialization ────────────────────────────────────────────────

  Future<void> _initPlayer(String url) async {
    if (!mounted) return;
    setState(() {
      _initializingPlayer = true;
      _hasError = false;
    });

    // Clean up old controller
    await _controller?.dispose();
    _controller = null;

    final ctrl = VideoPlayerController.networkUrl(
      Uri.parse(url),
      httpHeaders: const {
        'User-Agent':
            'Mozilla/5.0 (Linux; Android 11; Mobile) Streamfit/2.0',
      },
    );

    try {
      await ctrl.initialize();
      if (!mounted) {
        ctrl.dispose();
        return;
      }

      // Restore saved position
      final saved = context
          .read<HistoryProvider>()
          .getProgress(widget.subjectId, widget.season, widget.episode);
      if (saved != null &&
          saved.progressSeconds > 15 &&
          saved.durationSeconds > 0 &&
          saved.progress < 0.95) {
        await ctrl.seekTo(Duration(seconds: saved.progressSeconds));
      }

      setState(() {
        _controller = ctrl;
        _initializingPlayer = false;
      });

      ctrl.play();
      _startProgressTimer();
      _startHideTimer();
    } catch (e) {
      await ctrl.dispose();
      if (!mounted) return;
      setState(() {
        _initializingPlayer = false;
        _hasError = true;
        _errorMsg =
            'Cannot play this stream.\n\nTry selecting a different quality from the ⚙ settings button.';
      });
    }
  }

  // ── Timers ───────────────────────────────────────────────────────────────

  void _startProgressTimer() {
    _progressTimer?.cancel();
    _progressTimer =
        Timer.periodic(const Duration(seconds: 10), (_) => _saveProgress());
  }

  void _saveProgress() {
    final ctrl = _controller;
    if (ctrl == null || !ctrl.value.isInitialized) return;
    final pos = ctrl.value.position;
    final dur = ctrl.value.duration;
    if (pos.inSeconds < 5 || dur.inSeconds < 5) return;
    if (!mounted) return;
    context.read<HistoryProvider>().saveProgress(
          subjectId: widget.subjectId,
          title: widget.title,
          detailPath: widget.detailPath,
          coverUrl: widget.coverUrl,
          season: widget.season,
          episode: widget.episode,
          progressSeconds: pos.inSeconds,
          durationSeconds: dur.inSeconds,
        );
  }

  void _startHideTimer() {
    _hideTimer?.cancel();
    _hideTimer = Timer(const Duration(seconds: 3), () {
      if (mounted) setState(() => _showControls = false);
    });
  }

  // ── Controls ─────────────────────────────────────────────────────────────

  void _toggleControls() {
    setState(() => _showControls = !_showControls);
    if (_showControls) {
      _startHideTimer();
    } else {
      _hideTimer?.cancel();
    }
  }

  void _seek(int seconds) {
    final ctrl = _controller;
    if (ctrl == null) return;
    final pos = ctrl.value.position;
    final dur = ctrl.value.duration;
    final target = pos + Duration(seconds: seconds);
    final clamped = target < Duration.zero
        ? Duration.zero
        : (target > dur ? dur : target);
    ctrl.seekTo(clamped);

    setState(() {
      _seekLeft = seconds < 0;
      _seekRight = seconds > 0;
    });
    Future.delayed(const Duration(milliseconds: 700), () {
      if (mounted) setState(() { _seekLeft = false; _seekRight = false; });
    });
    _startHideTimer();
  }

  void _togglePlayPause() {
    final ctrl = _controller;
    if (ctrl == null) return;
    if (ctrl.value.isPlaying) {
      ctrl.pause();
    } else {
      ctrl.play();
      _startHideTimer();
    }
    setState(() {});
  }

  void _toggleFullscreen() {
    setState(() => _isFullscreen = !_isFullscreen);
    if (_isFullscreen) {
      SystemChrome.setPreferredOrientations([
        DeviceOrientation.landscapeLeft,
        DeviceOrientation.landscapeRight,
      ]);
      SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    } else {
      SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
      SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    }
  }

  void _showQualitySheet() {
    showModalBottomSheet(
      context: context,
      backgroundColor: AppColors.bgOverlay,
      shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(16))),
      builder: (_) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const SizedBox(height: 4),
            Container(
                width: 36,
                height: 4,
                decoration: BoxDecoration(
                    color: AppColors.border,
                    borderRadius: BorderRadius.circular(2))),
            const SizedBox(height: 16),
            Text('Select Quality',
                style: GoogleFonts.outfit(
                    fontSize: 16,
                    fontWeight: FontWeight.w800,
                    color: AppColors.textPrimary)),
            const SizedBox(height: 8),
            ..._resources.map((r) {
              final isSelected = _current?.resourceId == r.resourceId;
              return ListTile(
                leading: Icon(
                    isSelected
                        ? Icons.check_circle_rounded
                        : Icons.circle_outlined,
                    color: isSelected
                        ? AppColors.accent
                        : AppColors.textMuted,
                    size: 22),
                title: Text(r.label,
                    style: GoogleFonts.outfit(
                      color: isSelected
                          ? AppColors.textPrimary
                          : AppColors.textSecondary,
                      fontWeight: isSelected
                          ? FontWeight.w700
                          : FontWeight.w500,
                    )),
                onTap: () async {
                  Navigator.pop(context);
                  final savedPos = _controller?.value.position;
                  _current = r;
                  await _initPlayer(r.resourceLink);
                  if (savedPos != null && _controller != null) {
                    _controller!.seekTo(savedPos);
                  }
                },
              );
            }),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }

  // ── Build ─────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: _loadingResources
            ? _buildLoading('Loading stream...')
            : _hasError
                ? _buildError()
                : _buildPlayer(),
      ),
    );
  }

  Widget _buildLoading(String msg) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const SizedBox(
            width: 48,
            height: 48,
            child: CircularProgressIndicator(
                color: AppColors.accent, strokeWidth: 3),
          ),
          const SizedBox(height: 16),
          Text(msg,
              style: GoogleFonts.outfit(
                  color: AppColors.textSecondary, fontSize: 14)),
        ],
      ),
    );
  }

  Widget _buildError() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              padding: const EdgeInsets.all(20),
              decoration: BoxDecoration(
                color: AppColors.accent.withValues(alpha: 0.1),
                shape: BoxShape.circle,
              ),
              child: const Icon(Icons.signal_cellular_connected_no_internet_4_bar_rounded,
                  color: AppColors.accent, size: 48),
            ),
            const SizedBox(height: 20),
            Text(
              _errorMsg,
              textAlign: TextAlign.center,
              style: GoogleFonts.plusJakartaSans(
                  color: AppColors.textSecondary,
                  fontSize: 14,
                  height: 1.6),
            ),
            const SizedBox(height: 28),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                OutlinedButton.icon(
                  onPressed: () => Navigator.pop(context),
                  icon: const Icon(Icons.arrow_back_ios_new_rounded,
                      size: 14),
                  label: const Text('Go Back'),
                  style: OutlinedButton.styleFrom(
                    foregroundColor: AppColors.textSecondary,
                    side: const BorderSide(color: AppColors.border),
                    shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(50)),
                    padding: const EdgeInsets.symmetric(
                        horizontal: 20, vertical: 12),
                  ),
                ),
                const SizedBox(width: 12),
                ElevatedButton.icon(
                  onPressed: _loadResources,
                  icon: const Icon(Icons.refresh_rounded, size: 18),
                  label: const Text('Retry'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: AppColors.accent,
                    foregroundColor: Colors.white,
                    shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(50)),
                    padding: const EdgeInsets.symmetric(
                        horizontal: 24, vertical: 12),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildPlayer() {
    final ctrl = _controller;

    if (_initializingPlayer || ctrl == null) {
      return _buildLoading('Initializing player...');
    }

    return GestureDetector(
      onTap: _toggleControls,
      onDoubleTapDown: (d) {
        final half = MediaQuery.of(context).size.width / 2;
        _seek(d.localPosition.dx < half ? -10 : 10);
      },
      child: Stack(
        fit: StackFit.expand,
        children: [
          // Video
          ValueListenableBuilder<VideoPlayerValue>(
            valueListenable: ctrl,
            builder: (ctx, v, child) {
              if (!v.isInitialized) {
                return _buildLoading('Buffering...');
              }
              return Center(
                child: AspectRatio(
                  aspectRatio: v.aspectRatio,
                  child: VideoPlayer(ctrl),
                ),
              );
            },
          ),

          // Buffering indicator
          ValueListenableBuilder<VideoPlayerValue>(
            valueListenable: ctrl,
            builder: (ctx, v, child) {
              if (v.isBuffering && !v.isInitialized) return const SizedBox();
              if (v.isBuffering) {
                return const Center(
                  child: SizedBox(
                    width: 40,
                    height: 40,
                    child: CircularProgressIndicator(
                        color: AppColors.accent, strokeWidth: 3),
                  ),
                );
              }
              return const SizedBox();
            },
          ),

          // Controls overlay
          AnimatedOpacity(
            opacity: _showControls ? 1.0 : 0.0,
            duration: const Duration(milliseconds: 250),
            child: IgnorePointer(
              ignoring: !_showControls,
              child: _buildControls(ctrl),
            ),
          ),

          // Seek feedback
          if (_seekLeft)
            Positioned(
              left: 30,
              top: 0, bottom: 0,
              child: Center(child: _buildSeekBubble(false)),
            ),
          if (_seekRight)
            Positioned(
              right: 30,
              top: 0, bottom: 0,
              child: Center(child: _buildSeekBubble(true)),
            ),
        ],
      ),
    );
  }

  Widget _buildControls(VideoPlayerController ctrl) {
    return Container(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [
            Colors.black.withValues(alpha: 0.75),
            Colors.transparent,
            Colors.transparent,
            Colors.black.withValues(alpha: 0.85),
          ],
          stops: const [0.0, 0.25, 0.65, 1.0],
        ),
      ),
      child: Column(
        children: [
          // Top bar
          Padding(
            padding: const EdgeInsets.fromLTRB(8, 8, 8, 0),
            child: Row(
              children: [
                IconButton(
                  onPressed: () {
                    _saveProgress();
                    Navigator.pop(context);
                  },
                  icon: const Icon(Icons.arrow_back_ios_new_rounded,
                      color: Colors.white, size: 20),
                ),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        widget.title,
                        style: GoogleFonts.outfit(
                          color: Colors.white,
                          fontSize: 14,
                          fontWeight: FontWeight.w800,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      if (widget.season > 0)
                        Text(
                          'Season ${widget.season}  ·  Episode ${widget.episode}',
                          style: GoogleFonts.outfit(
                            color: AppColors.accent,
                            fontSize: 11,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                    ],
                  ),
                ),
                if (_resources.length > 1)
                  IconButton(
                    onPressed: _showQualitySheet,
                    icon: const Icon(Icons.settings_rounded,
                        color: Colors.white, size: 22),
                  ),
                IconButton(
                  onPressed: _toggleFullscreen,
                  icon: Icon(
                    _isFullscreen
                        ? Icons.fullscreen_exit_rounded
                        : Icons.fullscreen_rounded,
                    color: Colors.white,
                    size: 24,
                  ),
                ),
              ],
            ),
          ),

          const Spacer(),

          // Center play/pause
          ValueListenableBuilder<VideoPlayerValue>(
            valueListenable: ctrl,
            builder: (context, v, child) => IconButton(
              iconSize: 64,
              onPressed: _togglePlayPause,
              icon: Icon(
                v.isPlaying
                    ? Icons.pause_circle_filled_rounded
                    : Icons.play_circle_filled_rounded,
                color: Colors.white,
              ),
            ),
          ),

          const Spacer(),

          // Bottom bar: progress + time
          ValueListenableBuilder<VideoPlayerValue>(
            valueListenable: ctrl,
            builder: (ctx, v, child) {
              final pos = v.position;
              final dur = v.duration;
              final progress = dur.inMilliseconds > 0
                  ? (pos.inMilliseconds / dur.inMilliseconds).clamp(0.0, 1.0)
                  : 0.0;

              return Padding(
                padding: const EdgeInsets.fromLTRB(8, 0, 8, 16),
                child: Column(
                  children: [
                    SliderTheme(
                      data: SliderThemeData(
                        thumbShape: const RoundSliderThumbShape(
                            enabledThumbRadius: 7),
                        trackHeight: 3,
                        overlayShape: const RoundSliderOverlayShape(
                            overlayRadius: 14),
                        activeTrackColor: AppColors.accent,
                        inactiveTrackColor:
                            Colors.white.withValues(alpha: 0.25),
                        thumbColor: AppColors.accent,
                        overlayColor: AppColors.accent.withValues(alpha: 0.2),
                      ),
                      child: Slider(
                        value: progress,
                        onChanged: (v) {
                          ctrl.seekTo(Duration(
                              milliseconds:
                                  (v * dur.inMilliseconds).round()));
                        },
                        onChangeStart: (_) => _hideTimer?.cancel(),
                        onChangeEnd: (_) => _startHideTimer(),
                      ),
                    ),
                    Padding(
                      padding:
                          const EdgeInsets.symmetric(horizontal: 8),
                      child: Row(
                        mainAxisAlignment:
                            MainAxisAlignment.spaceBetween,
                        children: [
                          Text(_fmt(pos),
                              style: GoogleFonts.outfit(
                                  color: Colors.white,
                                  fontSize: 12,
                                  fontWeight: FontWeight.w600)),
                          Text(_fmt(dur),
                              style: GoogleFonts.outfit(
                                  color: Colors.white54,
                                  fontSize: 12)),
                        ],
                      ),
                    ),
                  ],
                ),
              );
            },
          ),
        ],
      ),
    );
  }

  Widget _buildSeekBubble(bool forward) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.55),
        borderRadius: BorderRadius.circular(50),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            forward
                ? Icons.fast_forward_rounded
                : Icons.fast_rewind_rounded,
            color: Colors.white,
            size: 28,
          ),
          const SizedBox(height: 2),
          Text(
            forward ? '+10s' : '-10s',
            style: GoogleFonts.outfit(
                color: Colors.white,
                fontSize: 12,
                fontWeight: FontWeight.w700),
          ),
        ],
      ),
    );
  }

  String _fmt(Duration d) {
    final h = d.inHours;
    final m = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    return h > 0 ? '$h:$m:$s' : '$m:$s';
  }
}
