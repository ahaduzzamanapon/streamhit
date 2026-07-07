import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:provider/provider.dart';
import 'package:video_player/video_player.dart';
import '../core/api_service.dart';
import '../core/constants.dart';
import '../models/subject.dart';
import '../providers/history_provider.dart';

// Native System Bridge for volume and brightness
class SystemService {
  static const _channel = MethodChannel('com.example.streamfit/system');

  static Future<int> getVolume() async {
    try {
      return await _channel.invokeMethod<int>('getVolume') ?? 7;
    } catch (_) {
      return 7;
    }
  }

  static Future<void> setVolume(int volume) async {
    try {
      await _channel.invokeMethod('setVolume', {'volume': volume});
    } catch (_) {}
  }

  static Future<int> getMaxVolume() async {
    try {
      return await _channel.invokeMethod<int>('getMaxVolume') ?? 15;
    } catch (_) {
      return 15;
    }
  }

  static Future<double> getBrightness() async {
    try {
      return await _channel.invokeMethod<double>('getBrightness') ?? 0.5;
    } catch (_) {
      return 0.5;
    }
  }

  static Future<void> setBrightness(double brightness) async {
    try {
      await _channel.invokeMethod('setBrightness', {'brightness': brightness});
    } catch (_) {}
  }
}

class WatchScreen extends StatefulWidget {
  final int subjectId;
  final String title;
  final String detailPath;
  final String? coverUrl;
  final int season;
  final int episode;
  final String? episodeName;
  final String? directUrl;
  final int totalEpisodes;
  final String? localPath; // Offline download path
  final List<Map<String, dynamic>>? audioOptions; // Persistent dub options passed from Details

  const WatchScreen({
    super.key,
    required this.subjectId,
    required this.title,
    required this.detailPath,
    this.coverUrl,
    this.season = 0,
    this.episode = 0,
    this.episodeName,
    this.directUrl,
    this.totalEpisodes = 0,
    this.localPath,
    this.audioOptions,
  });

  @override
  State<WatchScreen> createState() => _WatchScreenState();
}

class _WatchScreenState extends State<WatchScreen> {
  // Player
  VideoPlayerController? _controller;
  List<PlayResource> _resources = [];
  PlayResource? _current;

  // Active audio subject ID for language switching
  int? _activeAudioId;

  // State
  bool _loadingResources = true;
  bool _initializingPlayer = false;
  bool _hasError = false;
  String _errorMsg = '';

  // Controls visibility
  bool _showControls = true;
  bool _isLocked = false;
  Timer? _hideTimer;
  Timer? _progressTimer;

  // MX Player Gestures State
  bool _gesturesEnabled = true;
  double _brightnessValue = 0.5;
  int _systemVolumeValue = 7;
  int _maxSystemVolume = 15;
  int _boostVolumeValue = 0; // 0 to 15 (representing 100% to 200% software boost)

  // Active Gesture Display HUD values
  String? _hudType; // 'volume', 'brightness', 'seek'
  double _hudProgress = 0.0;
  String _hudText = '';

  // Temporary drag variables
  double? _dragStartVolume;
  double? _dragStartBrightness;
  Duration? _dragStartSeekTime;
  Duration? _dragCurrentSeekTarget;

  // Options
  double _playbackSpeed = 1.0;
  bool _fitFill = false;

  static const _speeds = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0];

  @override
  void initState() {
    super.initState();
    _activeAudioId = widget.subjectId;
    _initSystemValues();
    _loadResources();

    // Auto-enter fullscreen landscape
    SystemChrome.setPreferredOrientations([
      DeviceOrientation.landscapeLeft,
      DeviceOrientation.landscapeRight,
    ]);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  }

  Future<void> _initSystemValues() async {
    _maxSystemVolume = await SystemService.getMaxVolume();
    _systemVolumeValue = await SystemService.getVolume();
    _brightnessValue = await SystemService.getBrightness();
  }

  @override
  void dispose() {
    _hideTimer?.cancel();
    _progressTimer?.cancel();
    _controller?.dispose();

    // Restore portrait on exit
    SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    super.dispose();
  }

  Future<void> _loadResources() async {
    if (!mounted) return;
    setState(() {
      _loadingResources = true;
      _hasError = false;
      _errorMsg = '';
    });

    // Local play (offline download)
    if (widget.localPath != null) {
      _resources = [];
      _current = null;
      setState(() => _loadingResources = false);
      await _initPlayer(widget.localPath!, isLocal: true);
      return;
    }

    // Remote play (streaming)
    try {
      String activePath = widget.detailPath;
      if (widget.audioOptions != null && widget.audioOptions!.isNotEmpty) {
        final opt = widget.audioOptions!.firstWhere((o) => o['subjectId'] == _activeAudioId, orElse: () => widget.audioOptions!.first);
        activePath = opt['detailPath'];
      }

      final res = await ApiService.fetchPlayResources(
        _activeAudioId!,
        detailPath: activePath,
        se: widget.season > 0 ? widget.season : null,
        ep: widget.episode > 0 ? widget.episode : null,
      );

      if (!mounted) return;

      if (res.isEmpty) {
        setState(() {
          _loadingResources = false;
          _hasError = true;
          _errorMsg = 'No stream resources found.';
        });
        return;
      }

      _resources = res;
      _current = res.reduce((a, b) => a.resolution >= b.resolution ? a : b);
      setState(() => _loadingResources = false);
      await _initPlayer(_current!.resourceLink);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loadingResources = false;
        _hasError = true;
        _errorMsg = 'Failed to fetch streaming links.';
      });
    }
  }

  Future<void> _initPlayer(String url, {bool isLocal = false}) async {
    if (!mounted) return;
    setState(() { _initializingPlayer = true; });
    await _controller?.dispose();
    _controller = null;

    final ctrl = isLocal
        ? VideoPlayerController.file(File(url))
        : VideoPlayerController.networkUrl(
            Uri.parse(url),
            httpHeaders: const {'User-Agent': 'Mozilla/5.0 (Linux; Android 11; Mobile) Streamfit/2.0'},
          );

    try {
      await ctrl.initialize();
      if (!mounted) { ctrl.dispose(); return; }

      // Restore position if any
      final history = context.read<HistoryProvider>().getProgress(widget.subjectId, widget.season, widget.episode);
      if (history != null && history.progressSeconds > 15 && history.durationSeconds > 0 && history.progress < 0.95) {
        await ctrl.seekTo(Duration(seconds: history.progressSeconds));
      }

      await ctrl.setPlaybackSpeed(_playbackSpeed);
      await ctrl.setVolume(_boostVolumeValue > 0 ? 1.0 : 0.8);
      setState(() { _controller = ctrl; _initializingPlayer = false; });
      ctrl.play();
      _startProgressTimer();
      _startHideTimer();
    } catch (e) {
      await ctrl.dispose();
      if (!mounted) return;
      setState(() {
        _initializingPlayer = false;
        _hasError = true;
        _errorMsg = 'Decoder failed. Try switching quality.';
      });
    }
  }

  void _startProgressTimer() {
    _progressTimer?.cancel();
    _progressTimer = Timer.periodic(const Duration(seconds: 10), (_) {
      final ctrl = _controller;
      if (ctrl == null || !ctrl.value.isInitialized || !mounted) return;
      final pos = ctrl.value.position;
      final dur = ctrl.value.duration;
      if (pos.inSeconds < 5 || dur.inSeconds < 5) return;
      context.read<HistoryProvider>().saveProgress(
        subjectId: widget.subjectId, title: widget.title, detailPath: widget.detailPath,
        coverUrl: widget.coverUrl, season: widget.season, episode: widget.episode,
        progressSeconds: pos.inSeconds, durationSeconds: dur.inSeconds,
      );
    });
  }

  void _startHideTimer() {
    _hideTimer?.cancel();
    _hideTimer = Timer(const Duration(seconds: 4), () {
      if (mounted && !_isLocked) setState(() => _showControls = false);
    });
  }

  void _toggleControls() {
    if (_isLocked) {
      setState(() => _showControls = true);
      _hideTimer?.cancel();
      _hideTimer = Timer(const Duration(seconds: 2), () {
        if (mounted) setState(() => _showControls = false);
      });
      return;
    }
    setState(() => _showControls = !_showControls);
    if (_showControls) _startHideTimer();
    else _hideTimer?.cancel();
  }

  void _seek(int seconds) {
    final ctrl = _controller;
    if (ctrl == null) return;
    final pos = ctrl.value.position;
    final dur = ctrl.value.duration;
    final target = pos + Duration(seconds: seconds);
    ctrl.seekTo(target < Duration.zero ? Duration.zero : (target > dur ? dur : target));
  }

  void _togglePlayPause() {
    final ctrl = _controller;
    if (ctrl == null) return;
    if (ctrl.value.isPlaying) { ctrl.pause(); } else { ctrl.play(); }
    setState(() {});
  }

  // ─── MX Gesture Handlers (Separated Vertical & Horizontal) ─────────────────

  void _handleVerticalDragStart(DragStartDetails d) {
    if (!_gesturesEnabled || _isLocked || _controller == null || !_controller!.value.isInitialized) return;
    final size = MediaQuery.of(context).size;
    final localX = d.localPosition.dx;
    final isLeft = localX < (size.width / 2);

    _dragStartVolume = null;
    _dragStartBrightness = null;

    if (isLeft) {
      _dragStartVolume = (_systemVolumeValue + _boostVolumeValue).toDouble();
    } else {
      _dragStartBrightness = _brightnessValue;
    }
  }

  void _handleVerticalDragUpdate(DragUpdateDetails d) {
    if (!_gesturesEnabled || _isLocked || _controller == null || !_controller!.value.isInitialized) return;
    final size = MediaQuery.of(context).size;
    final sensitivity = 2.0;

    if (_dragStartVolume != null) {
      final delta = -d.delta.dy / size.height * sensitivity;
      final totalMaxVolume = _maxSystemVolume + 15;
      final targetVolume = (_dragStartVolume! + (delta * totalMaxVolume)).clamp(0.0, totalMaxVolume.toDouble());
      
      final roundedVol = targetVolume.round();
      if (roundedVol <= _maxSystemVolume) {
        _systemVolumeValue = roundedVol;
        _boostVolumeValue = 0;
        SystemService.setVolume(roundedVol);
        _controller?.setVolume(0.8);
      } else {
        _systemVolumeValue = _maxSystemVolume;
        _boostVolumeValue = roundedVol - _maxSystemVolume;
        SystemService.setVolume(_maxSystemVolume);
        final extraGain = 0.8 + (0.2 * (_boostVolumeValue / 15.0));
        _controller?.setVolume(extraGain);
      }

      final percentage = ((roundedVol / totalMaxVolume) * 100).round();
      setState(() {
        _hudType = 'volume';
        _hudProgress = roundedVol / totalMaxVolume;
        _hudText = roundedVol > _maxSystemVolume 
            ? 'Volume: Boost ${100 + ((_boostVolumeValue / 15) * 100).round()}%' 
            : 'Volume: $percentage%';
      });
    } else if (_dragStartBrightness != null) {
      final delta = -d.delta.dy / size.height * sensitivity;
      final targetBrightness = (_dragStartBrightness! + delta).clamp(0.0, 1.0);
      _brightnessValue = targetBrightness;
      SystemService.setBrightness(targetBrightness);
      setState(() {
        _hudType = 'brightness';
        _hudProgress = targetBrightness;
        _hudText = 'Brightness: ${(targetBrightness * 100).round()}%';
      });
    }
  }

  void _handleVerticalDragEnd(DragEndDetails d) {
    setState(() {
      _hudType = null;
      _dragStartVolume = null;
      _dragStartBrightness = null;
    });
  }

  void _handleHorizontalDragStart(DragStartDetails d) {
    if (!_gesturesEnabled || _isLocked || _controller == null || !_controller!.value.isInitialized) return;
    _dragStartSeekTime = _controller!.value.position;
    _dragCurrentSeekTarget = _dragStartSeekTime;
  }

  void _handleHorizontalDragUpdate(DragUpdateDetails d) {
    if (!_gesturesEnabled || _isLocked || _controller == null || !_controller!.value.isInitialized || _dragStartSeekTime == null) return;
    final size = MediaQuery.of(context).size;
    final duration = _controller!.value.duration;
    
    final deltaSecs = (d.delta.dx / size.width) * duration.inSeconds * 0.8;
    final targetSecs = (_dragCurrentSeekTarget!.inSeconds + deltaSecs.round()).clamp(0, duration.inSeconds);
    
    _dragCurrentSeekTarget = Duration(seconds: targetSecs);
    final diff = _dragCurrentSeekTarget!.inSeconds - _dragStartSeekTime!.inSeconds;
    final diffStr = diff >= 0 ? '+${_fmtSecs(diff)}' : '-${_fmtSecs(diff.abs())}';

    setState(() {
      _hudType = 'seek';
      _hudText = '${_fmt(_dragCurrentSeekTarget!)} / ${_fmt(duration)}  [ $diffStr ]';
    });
  }

  void _handleHorizontalDragEnd(DragEndDetails d) {
    if (_dragCurrentSeekTarget != null) {
      _controller?.seekTo(_dragCurrentSeekTarget!);
    }
    setState(() {
      _hudType = null;
      _dragStartSeekTime = null;
      _dragCurrentSeekTarget = null;
    });
  }

  String _fmtSecs(int secs) {
    final m = secs ~/ 60;
    final s = secs % 60;
    return m > 0 ? '$m:${s.toString().padLeft(2, '0')}' : '$s';
  }

  // ─── Modal sheets (Speed, Quality, Language selector) ─────────────────────

  void _showQualitySheet() {
    showModalBottomSheet(
      context: context,
      backgroundColor: const Color(0xFF1A1A2E),
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(16))),
      builder: (_) => SafeArea(child: Column(mainAxisSize: MainAxisSize.min, children: [
        const SizedBox(height: 4),
        Container(width: 36, height: 4, decoration: BoxDecoration(color: AppColors.border, borderRadius: BorderRadius.circular(2))),
        const SizedBox(height: 16),
        Text('Select Quality', style: GoogleFonts.outfit(fontSize: 16, fontWeight: FontWeight.w800, color: AppColors.textPrimary)),
        const SizedBox(height: 8),
        ..._resources.map((r) {
          final isSel = _current?.resourceId == r.resourceId;
          return ListTile(
            leading: Icon(isSel ? Icons.check_circle_rounded : Icons.circle_outlined, color: isSel ? AppColors.accent : AppColors.textMuted, size: 22),
            title: Text(r.label, style: GoogleFonts.outfit(color: isSel ? AppColors.textPrimary : AppColors.textSecondary, fontWeight: isSel ? FontWeight.w700 : FontWeight.w500)),
            onTap: () async {
              Navigator.pop(context);
              final savedPos = _controller?.value.position;
              _current = r;
              await _initPlayer(r.resourceLink);
              if (savedPos != null && _controller != null) _controller!.seekTo(savedPos);
            },
          );
        }),
        const SizedBox(height: 8),
      ])),
    );
  }

  void _showSpeedSheet() {
    showModalBottomSheet(
      context: context,
      backgroundColor: const Color(0xFF1A1A2E),
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(16))),
      builder: (_) => SafeArea(child: Column(mainAxisSize: MainAxisSize.min, children: [
        const SizedBox(height: 4),
        Container(width: 36, height: 4, decoration: BoxDecoration(color: AppColors.border, borderRadius: BorderRadius.circular(2))),
        const SizedBox(height: 16),
        Text('Playback Speed', style: GoogleFonts.outfit(fontSize: 16, fontWeight: FontWeight.w800, color: AppColors.textPrimary)),
        const SizedBox(height: 12),
        Wrap(
          spacing: 10, runSpacing: 10, alignment: WrapAlignment.center,
          children: _speeds.map((s) {
            final isSel = _playbackSpeed == s;
            return GestureDetector(
              onTap: () { setState(() => _playbackSpeed = s); _controller?.setPlaybackSpeed(s); Navigator.pop(context); },
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 9),
                decoration: BoxDecoration(
                  color: isSel ? AppColors.accent : AppColors.bgOverlay,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: isSel ? AppColors.accent : AppColors.border),
                ),
                child: Text('${s}x', style: GoogleFonts.outfit(color: Colors.white, fontWeight: isSel ? FontWeight.w800 : FontWeight.w500)),
              ),
            );
          }).toList(),
        ),
        const SizedBox(height: 20),
      ])),
    );
  }

  void _showLanguageSheet() {
    if (widget.audioOptions == null || widget.audioOptions!.isEmpty) return;
    showModalBottomSheet(
      context: context,
      backgroundColor: const Color(0xFF1A1A2E),
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(16))),
      builder: (_) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const SizedBox(height: 4),
            Container(width: 36, height: 4, decoration: BoxDecoration(color: AppColors.border, borderRadius: BorderRadius.circular(2))),
            const SizedBox(height: 16),
            Text('Select Audio Language', style: GoogleFonts.outfit(fontSize: 16, fontWeight: FontWeight.w800, color: AppColors.textPrimary)),
            const SizedBox(height: 8),
            ...widget.audioOptions!.map((opt) {
              final optId = opt['subjectId'] as int;
              final isSel = (_activeAudioId ?? widget.subjectId) == optId;
              return ListTile(
                leading: Icon(isSel ? Icons.check_circle_rounded : Icons.circle_outlined, color: isSel ? AppColors.accent : AppColors.textMuted, size: 22),
                title: Text(opt['lanName'], style: GoogleFonts.outfit(color: isSel ? AppColors.textPrimary : AppColors.textSecondary, fontWeight: isSel ? FontWeight.w700 : FontWeight.w500)),
                onTap: () async {
                  Navigator.pop(context);
                  if (optId == _activeAudioId) return;
                  final savedPos = _controller?.value.position;
                  setState(() {
                    _activeAudioId = optId;
                    _loadingResources = true;
                  });
                  await _loadResources();
                  if (savedPos != null && _controller != null) {
                    await _controller!.seekTo(savedPos);
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

  // ─── Build UI ──────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: WillPopScope(
        onWillPop: () async {
          // Restore orientations on physical back press
          SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
          SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
          return true;
        },
        child: GestureDetector(
          onVerticalDragStart: _handleVerticalDragStart,
          onVerticalDragUpdate: _handleVerticalDragUpdate,
          onVerticalDragEnd: _handleVerticalDragEnd,
          onHorizontalDragStart: _handleHorizontalDragStart,
          onHorizontalDragUpdate: _handleHorizontalDragUpdate,
          onHorizontalDragEnd: _handleHorizontalDragEnd,
          onTap: _toggleControls,
          child: Stack(
            fit: StackFit.expand,
            children: [
              // Player
              if (_controller != null && _controller!.value.isInitialized)
                Center(
                  child: AspectRatio(
                    aspectRatio: _fitFill ? (MediaQuery.of(context).size.width / MediaQuery.of(context).size.height) : _controller!.value.aspectRatio,
                    child: _fitFill
                        ? FittedBox(fit: BoxFit.fill, child: SizedBox(width: _controller!.value.size.width, height: _controller!.value.size.height, child: VideoPlayer(_controller!)))
                        : VideoPlayer(_controller!),
                  ),
                )
              else
                const SizedBox.shrink(),

              // Buffering indicator
              if (_controller != null && _controller!.value.isBuffering)
                const Center(child: CircularProgressIndicator(color: AppColors.accent, strokeWidth: 3)),

              // Loader states
              if (_loadingResources || _initializingPlayer)
                _buildLoader(),
              if (_hasError)
                _buildErrorView(),

              // Control overlays
              if (_controller != null && _controller!.value.isInitialized && !_hasError)
                AnimatedOpacity(
                  opacity: _showControls ? 1.0 : 0.0,
                  duration: const Duration(milliseconds: 200),
                  child: IgnorePointer(
                    ignoring: !_showControls,
                    child: _isLocked ? _buildLockedOverlay() : _buildFullscreenControls(),
                  ),
                ),

              // Gesture indicator HUD
              if (_hudType != null) _buildGestureHUD(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildLoader() {
    return Container(
      color: Colors.black87,
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const CircularProgressIndicator(color: AppColors.accent, strokeWidth: 3),
            const SizedBox(height: 16),
            Text('Loading resource stream...', style: GoogleFonts.outfit(color: Colors.white, fontSize: 13)),
          ],
        ),
      ),
    );
  }

  Widget _buildErrorView() {
    return Container(
      color: Colors.black87,
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.error_outline_rounded, color: AppColors.accent, size: 48),
            const SizedBox(height: 16),
            Text(_errorMsg, style: GoogleFonts.outfit(color: Colors.white, fontSize: 14)),
            const SizedBox(height: 20),
            ElevatedButton(
              onPressed: _loadResources,
              style: ElevatedButton.styleFrom(backgroundColor: AppColors.accent, foregroundColor: Colors.white),
              child: const Text('Retry'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildFullscreenControls() {
    final ctrl = _controller!;
    final qualityLabel = _current?.resolution != null ? '${_current!.resolution}P' : 'AUTO';

    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter, end: Alignment.bottomCenter,
          colors: [Color(0xCC000000), Colors.transparent, Colors.transparent, Color(0xCC000000)],
          stops: [0.0, 0.25, 0.65, 1.0],
        ),
      ),
      child: Stack(
        children: [
          // TOP BAR
          Positioned(
            top: 0, left: 0, right: 0,
            child: GestureDetector(
              onTap: () {}, // Intercept clicks
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                child: Row(
                  children: [
                    IconButton(
                      onPressed: () => Navigator.pop(context),
                      icon: const Icon(Icons.arrow_back_ios_new_rounded, color: Colors.white, size: 20),
                    ),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(widget.title, style: GoogleFonts.outfit(color: Colors.white, fontSize: 15, fontWeight: FontWeight.w800), maxLines: 1, overflow: TextOverflow.ellipsis),
                          if (widget.season > 0)
                            Text('Season ${widget.season.toString().padLeft(2,'0')} Episode ${widget.episode.toString().padLeft(2,'0')}',
                                style: GoogleFonts.outfit(color: AppColors.accent, fontSize: 11, fontWeight: FontWeight.w700)),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),

          // Lock button (Left center)
          Positioned(
            left: 16, top: 0, bottom: 0,
            child: Center(
              child: GestureDetector(
                onTap: () => setState(() => _isLocked = true),
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                  decoration: BoxDecoration(color: Colors.black54, borderRadius: BorderRadius.circular(50)),
                  child: Row(
                    children: [
                      const Icon(Icons.lock_open_rounded, color: Colors.white, size: 16),
                      const SizedBox(width: 6),
                      Text('Lock', style: GoogleFonts.outfit(color: Colors.white, fontSize: 11, fontWeight: FontWeight.w600)),
                    ],
                  ),
                ),
              ),
            ),
          ),

          // Center Controls
          Center(
            child: GestureDetector(
              onTap: () {},
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  _buildCenterBtn(Icons.replay_10_rounded, () => _seek(-10)),
                  const SizedBox(width: 32),
                  GestureDetector(
                    onTap: _togglePlayPause,
                    child: Container(
                      width: 64, height: 64,
                      decoration: BoxDecoration(color: Colors.white.withValues(alpha: 0.2), shape: BoxShape.circle, border: Border.all(color: Colors.white60, width: 2)),
                      child: Icon(ctrl.value.isPlaying ? Icons.pause_rounded : Icons.play_arrow_rounded, color: Colors.white, size: 36),
                    ),
                  ),
                  const SizedBox(width: 32),
                  _buildCenterBtn(Icons.forward_10_rounded, () => _seek(10)),
                ],
              ),
            ),
          ),

          // Bottom Controls & Slider
          Positioned(
            bottom: 0, left: 0, right: 0,
            child: GestureDetector(
              onTap: () {},
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    ValueListenableBuilder<VideoPlayerValue>(
                      valueListenable: ctrl,
                      builder: (_, v, __) {
                        final pos = v.position; final dur = v.duration;
                        final progress = dur.inMilliseconds > 0 ? (pos.inMilliseconds / dur.inMilliseconds).clamp(0.0, 1.0) : 0.0;
                        return Row(
                          children: [
                            Text(_fmt(pos), style: GoogleFonts.outfit(color: Colors.white, fontSize: 11, fontWeight: FontWeight.w600)),
                            Expanded(
                              child: SliderTheme(
                                data: SliderThemeData(
                                  thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 6),
                                  trackHeight: 2.5, overlayShape: const RoundSliderOverlayShape(overlayRadius: 12),
                                  activeTrackColor: AppColors.accent, inactiveTrackColor: Colors.white30,
                                  thumbColor: Colors.white, overlayColor: AppColors.accent.withValues(alpha: 0.2),
                                ),
                                child: Slider(
                                  value: progress,
                                  onChanged: (val) => ctrl.seekTo(Duration(milliseconds: (val * dur.inMilliseconds).round())),
                                ),
                              ),
                            ),
                            Text(_fmt(dur), style: GoogleFonts.outfit(color: Colors.white60, fontSize: 11)),
                          ],
                        );
                      },
                    ),
                    Row(
                      children: [
                        IconButton(
                          onPressed: _togglePlayPause, padding: EdgeInsets.zero, constraints: const BoxConstraints(),
                          icon: Icon(ctrl.value.isPlaying ? Icons.pause_rounded : Icons.play_arrow_rounded, color: Colors.white, size: 26),
                        ),
                        const Spacer(),
                        _buildTextBtn(_fitFill ? 'Fill' : 'Fit', () => setState(() => _fitFill = !_fitFill)),
                        const SizedBox(width: 8),
                        _buildTextBtn('${_playbackSpeed == 1.0 ? '1' : _playbackSpeed}x', _showSpeedSheet),
                        const SizedBox(width: 8),
                        if (widget.audioOptions != null && widget.audioOptions!.length > 1) ...[
                          _buildTextBtn('Language', _showLanguageSheet),
                          const SizedBox(width: 8),
                        ],
                        if (qualityLabel != 'AUTO' || _resources.length > 1) ...[
                          _buildTextBtn(qualityLabel, _showQualitySheet),
                          const SizedBox(width: 8),
                        ],
                        IconButton(
                          onPressed: () => setState(() => _gesturesEnabled = !_gesturesEnabled),
                          padding: EdgeInsets.zero, constraints: const BoxConstraints(),
                          icon: Icon(_gesturesEnabled ? Icons.gesture_rounded : Icons.block_flipped, color: Colors.white, size: 20),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildLockedOverlay() {
    return Container(
      color: Colors.black45,
      child: Stack(
        children: [
          Positioned(
            left: 16, top: 0, bottom: 0,
            child: Center(
              child: GestureDetector(
                onTap: () { setState(() => _isLocked = false); _startHideTimer(); },
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
                  decoration: BoxDecoration(color: Colors.black54, borderRadius: BorderRadius.circular(50)),
                  child: Row(
                    children: [
                      const Icon(Icons.lock_rounded, color: Colors.white, size: 18),
                      const SizedBox(width: 6),
                      Text('Unlock', style: GoogleFonts.outfit(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w600)),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildGestureHUD() {
    IconData iconData;
    Color color = AppColors.accent;

    if (_hudType == 'volume') {
      iconData = _boostVolumeValue > 0 ? Icons.volume_up_rounded : Icons.volume_down_rounded;
      if (_boostVolumeValue > 0) color = AppColors.accentGold;
    } else if (_hudType == 'brightness') {
      iconData = Icons.brightness_medium_rounded;
    } else {
      iconData = Icons.compare_arrows_rounded;
    }

    return Positioned(
      top: 40,
      left: 0, right: 0,
      child: Center(
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          decoration: BoxDecoration(
            color: Colors.black.withValues(alpha: 0.8),
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: color.withValues(alpha: 0.4), width: 1),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(iconData, color: color, size: 20),
                  const SizedBox(width: 8),
                  Text(_hudText, style: GoogleFonts.outfit(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w800)),
                ],
              ),
              if (_hudType != 'seek') ...[
                const SizedBox(height: 6),
                SizedBox(
                  width: 120,
                  height: 3,
                  child: LinearProgressIndicator(
                    value: _hudProgress,
                    backgroundColor: Colors.white12,
                    valueColor: AlwaysStoppedAnimation<Color>(color),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildCenterBtn(IconData icon, VoidCallback onTap) => GestureDetector(
    onTap: onTap,
    child: Container(
      width: 40, height: 40,
      decoration: const BoxDecoration(color: Colors.black45, shape: BoxShape.circle),
      child: Icon(icon, color: Colors.white, size: 24),
    ),
  );

  Widget _buildTextBtn(String label, VoidCallback onTap) => GestureDetector(
    onTap: onTap,
    child: Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(color: Colors.white.withValues(alpha: 0.15), borderRadius: BorderRadius.circular(4), border: Border.all(color: Colors.white.withValues(alpha: 0.25))),
      child: Text(label, style: GoogleFonts.outfit(color: Colors.white, fontSize: 10, fontWeight: FontWeight.w700)),
    ),
  );

  String _fmt(Duration d) {
    final h = d.inHours;
    final m = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    return h > 0 ? '$h:$m:$s' : '$m:$s';
  }
}
