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
  });

  @override
  State<WatchScreen> createState() => _WatchScreenState();
}

class _WatchScreenState extends State<WatchScreen> {
  // Player
  VideoPlayerController? _controller;
  List<PlayResource> _resources = [];
  PlayResource? _current;

  // State
  bool _loadingResources = true;
  bool _initializingPlayer = false;
  bool _hasError = false;
  String _errorMsg = '';

  // Controls visibility
  bool _showControls = true;
  bool _isLocked = false;
  bool _isFullscreen = false;
  Timer? _hideTimer;
  Timer? _progressTimer;

  // MX Player Gestures State
  bool _gesturesEnabled = true; // Multi-adjust toggle button state
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
  String? _selectedLanguage;

  List<String> get _availableLanguages {
    final langs = _resources.map((r) => r.language ?? 'Original').toSet().toList();
    langs.sort();
    return langs;
  }

  List<PlayResource> get _filteredResources {
    if (_selectedLanguage == null || _availableLanguages.length <= 1) return _resources;
    return _resources.where((r) => (r.language ?? 'Original') == _selectedLanguage).toList();
  }

  static const _speeds = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0];

  @override
  void initState() {
    super.initState();
    _initSystemValues();
    _loadResources();
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
    if (_isFullscreen) {
      SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
      SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    }
    super.dispose();
  }

  // ─── Fullscreen ────────────────────────────────────────────────────────────

  void _enterFullscreen() {
    SystemChrome.setPreferredOrientations([
      DeviceOrientation.landscapeLeft,
      DeviceOrientation.landscapeRight,
    ]);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    setState(() => _isFullscreen = true);
    _startHideTimer();
  }

  void _exitFullscreen() {
    SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    setState(() { _isFullscreen = false; _showControls = true; });
    _hideTimer?.cancel();
  }

  // ─── Resource Loading ──────────────────────────────────────────────────────

  Future<void> _loadResources() async {
    if (!mounted) return;
    setState(() { _loadingResources = true; _hasError = false; _errorMsg = ''; });

    // Local file playing
    if (widget.localPath != null && widget.localPath!.isNotEmpty) {
      _resources = [PlayResource(resourceId: 'local', resourceLink: widget.localPath!, resolution: 0, language: 'Offline')];
      _current = _resources.first;
      setState(() => _loadingResources = false);
      await _initPlayer(_current!.resourceLink, isLocalFile: true);
      return;
    }

    if (widget.directUrl != null && widget.directUrl!.isNotEmpty) {
      _resources = [PlayResource(resourceId: 'live', resourceLink: widget.directUrl!, resolution: 0, language: null)];
      _current = _resources.first;
      setState(() => _loadingResources = false);
      await _initPlayer(_current!.resourceLink);
      return;
    }

    try {
      final resources = await ApiService.fetchPlayResources(
        widget.subjectId, detailPath: widget.detailPath,
        se: widget.season > 0 ? widget.season : null,
        ep: widget.episode > 0 ? widget.episode : null,
      );
      if (!mounted) return;
      if (resources.isEmpty) {
        setState(() {
          _loadingResources = false; _hasError = true;
          _errorMsg = 'No stream available for this content.\nPlease try again later or select a different episode.';
        });
        return;
      }
      _resources = resources;
      _current = _filteredResources.isNotEmpty
          ? _filteredResources.reduce((a, b) => a.resolution >= b.resolution ? a : b)
          : resources.first;
      setState(() => _loadingResources = false);
      await _initPlayer(_current!.resourceLink);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loadingResources = false; _hasError = true;
        _errorMsg = 'Connection error. Check your internet and try again.';
      });
    }
  }

  // ─── Player ────────────────────────────────────────────────────────────────

  Future<void> _initPlayer(String url, {bool isLocalFile = false}) async {
    if (!mounted) return;
    setState(() { _initializingPlayer = true; _hasError = false; });
    await _controller?.dispose();
    _controller = null;

    final ctrl = isLocalFile
        ? VideoPlayerController.file(File(url))
        : VideoPlayerController.networkUrl(
            Uri.parse(url),
            httpHeaders: const {'User-Agent': 'Mozilla/5.0 (Linux; Android 11; Mobile) Streamfit/2.0'},
          );
    try {
      await ctrl.initialize();
      if (!mounted) { ctrl.dispose(); return; }

      final saved = context.read<HistoryProvider>().getProgress(widget.subjectId, widget.season, widget.episode);
      if (saved != null && saved.progressSeconds > 15 && saved.durationSeconds > 0 && saved.progress < 0.95) {
        await ctrl.seekTo(Duration(seconds: saved.progressSeconds));
      }
      await ctrl.setPlaybackSpeed(_playbackSpeed);
      // Initialize controller volume based on boosted volume state
      await ctrl.setVolume(_boostVolumeValue > 0 ? 1.0 : 0.8);
      setState(() { _controller = ctrl; _initializingPlayer = false; });
      ctrl.play();
      _startProgressTimer();
    } catch (e) {
      await ctrl.dispose();
      if (!mounted) return;
      setState(() {
        _initializingPlayer = false; _hasError = true;
        _errorMsg = 'Cannot play this stream.\n\nTry a different quality from the ⚙ button.';
      });
    }
  }

  // ─── Timers ────────────────────────────────────────────────────────────────

  void _startProgressTimer() {
    _progressTimer?.cancel();
    _progressTimer = Timer.periodic(const Duration(seconds: 10), (_) => _saveProgress());
  }

  void _saveProgress() {
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
  }

  void _startHideTimer() {
    _hideTimer?.cancel();
    _hideTimer = Timer(const Duration(seconds: 4), () {
      if (mounted && !_isLocked) setState(() => _showControls = false);
    });
  }

  // ─── Controls ──────────────────────────────────────────────────────────────

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
    if (_showControls && _isFullscreen) _startHideTimer();
    else _hideTimer?.cancel();
  }

  void _seek(int seconds) {
    final ctrl = _controller;
    if (ctrl == null) return;
    final pos = ctrl.value.position;
    final dur = ctrl.value.duration;
    final target = pos + Duration(seconds: seconds);
    ctrl.seekTo(target < Duration.zero ? Duration.zero : (target > dur ? dur : target));
    if (_isFullscreen) _startHideTimer();
  }

  void _togglePlayPause() {
    final ctrl = _controller;
    if (ctrl == null) return;
    if (ctrl.value.isPlaying) { ctrl.pause(); } else { ctrl.play(); if (_isFullscreen) _startHideTimer(); }
    setState(() {});
  }

  void _nextEpisode() {
    if (widget.season <= 0 || widget.episode <= 0) return;
    final next = widget.episode + 1;
    if (widget.totalEpisodes > 0 && next > widget.totalEpisodes) return;
    _saveProgress();
    Navigator.pushReplacement(context, MaterialPageRoute(
      builder: (_) => WatchScreen(
        subjectId: widget.subjectId, title: widget.title, detailPath: widget.detailPath,
        coverUrl: widget.coverUrl, season: widget.season, episode: next,
        totalEpisodes: widget.totalEpisodes,
        localPath: widget.localPath,
      ),
    ));
  }

  // ─── MX Gesture Handlers ────────────────────────────────────────────────────

  void _handleDragStart(DragStartDetails d) {
    if (!_gesturesEnabled || _isLocked || _controller == null || !_controller!.value.isInitialized) return;
    
    final size = MediaQuery.of(context).size;
    final localX = d.localPosition.dx;
    final isLeftHalf = localX < (size.width / 2);

    _dragStartVolume = null;
    _dragStartBrightness = null;
    _dragStartSeekTime = null;

    if (isLeftHalf) {
      // Left side: Volume
      _dragStartVolume = (_systemVolumeValue + _boostVolumeValue).toDouble();
    } else {
      // Right side: Brightness
      _dragStartBrightness = _brightnessValue;
    }
  }

  void _handleDragUpdate(DragUpdateDetails d) {
    if (!_gesturesEnabled || _isLocked || _controller == null || !_controller!.value.isInitialized) return;

    final size = MediaQuery.of(context).size;
    final sensitivity = 1.5; // Drag sensitivity multiplier

    // 1. Horizontal Drag: Seek/Progress
    if (d.delta.dx.abs() > d.delta.dy.abs() && _dragStartVolume == null && _dragStartBrightness == null) {
      if (_dragStartSeekTime == null) {
        _dragStartSeekTime = _controller!.value.position;
        _dragCurrentSeekTarget = _dragStartSeekTime;
      }
      final duration = _controller!.value.duration;
      final dragProgress = d.delta.dx / size.width * duration.inSeconds * sensitivity;
      final targetSecs = (_dragCurrentSeekTarget!.inSeconds + dragProgress.round())
          .clamp(0, duration.inSeconds);
      
      _dragCurrentSeekTarget = Duration(seconds: targetSecs);
      final diff = _dragCurrentSeekTarget!.inSeconds - _dragStartSeekTime!.inSeconds;
      final diffStr = diff >= 0 ? '+${_fmtSecs(diff)}' : '-${_fmtSecs(diff.abs())}';

      setState(() {
        _hudType = 'seek';
        _hudText = '${_fmt(_dragCurrentSeekTarget!)} / ${_fmt(duration)}  [ $diffStr ]';
      });
      return;
    }

    // 2. Vertical Drag: Volume
    if (_dragStartVolume != null) {
      final delta = -d.delta.dy / size.height * sensitivity;
      // Normal max volume is 15. We add 15 boost levels (total max 30)
      final totalMaxVolume = _maxSystemVolume + 15;
      final targetVolume = (_dragStartVolume! + (delta * totalMaxVolume)).clamp(0.0, totalMaxVolume.toDouble());
      
      final roundedVol = targetVolume.round();
      if (roundedVol <= _maxSystemVolume) {
        _systemVolumeValue = roundedVol;
        _boostVolumeValue = 0;
        SystemService.setVolume(roundedVol);
        _controller?.setVolume(0.8); // Normal volume
      } else {
        _systemVolumeValue = _maxSystemVolume;
        _boostVolumeValue = roundedVol - _maxSystemVolume;
        SystemService.setVolume(_maxSystemVolume);
        // Map boost volume to player volume up to 1.0 (Audio Boost!)
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
      return;
    }

    // 3. Vertical Drag: Brightness
    if (_dragStartBrightness != null) {
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

  void _handleDragEnd(DragEndDetails d) {
    if (_hudType == 'seek' && _dragCurrentSeekTarget != null) {
      _controller?.seekTo(_dragCurrentSeekTarget!);
    }
    setState(() {
      _hudType = null;
      _dragStartVolume = null;
      _dragStartBrightness = null;
      _dragStartSeekTime = null;
      _dragCurrentSeekTarget = null;
    });
  }

  String _fmtSecs(int secs) {
    final m = secs ~/ 60;
    final s = secs % 60;
    return m > 0 ? '$m:${s.toString().padLeft(2, '0')}' : '$s';
  }

  // ─── Sheets ────────────────────────────────────────────────────────────────

  void _showQualitySheet() {
    final filtered = _filteredResources;
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
        ...filtered.map((r) {
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
    showModalBottomSheet(
      context: context,
      backgroundColor: const Color(0xFF1A1A2E),
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(16))),
      builder: (_) => SafeArea(child: Column(mainAxisSize: MainAxisSize.min, children: [
        const SizedBox(height: 4),
        Container(width: 36, height: 4, decoration: BoxDecoration(color: AppColors.border, borderRadius: BorderRadius.circular(2))),
        const SizedBox(height: 16),
        Text('Select Audio Language', style: GoogleFonts.outfit(fontSize: 16, fontWeight: FontWeight.w800, color: AppColors.textPrimary)),
        const SizedBox(height: 8),
        ..._availableLanguages.map((lang) {
          final isSel = (_selectedLanguage ?? _availableLanguages.first) == lang;
          return ListTile(
            leading: Icon(isSel ? Icons.check_circle_rounded : Icons.circle_outlined, color: isSel ? AppColors.accent : AppColors.textMuted, size: 22),
            title: Text(lang, style: GoogleFonts.outfit(color: isSel ? AppColors.textPrimary : AppColors.textSecondary, fontWeight: isSel ? FontWeight.w700 : FontWeight.w500)),
            onTap: () async {
              Navigator.pop(context);
              setState(() {
                _selectedLanguage = lang;
                final filtered = _resources.where((r) => (r.language ?? 'Original') == lang).toList();
                if (filtered.isNotEmpty) {
                  _current = filtered.reduce((a, b) => a.resolution >= b.resolution ? a : b);
                  _initPlayer(_current!.resourceLink);
                }
              });
            },
          );
        }),
        const SizedBox(height: 8),
      ])),
    );
  }

  // ─── Build ─────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    return WillPopScope(
      onWillPop: () async {
        if (_isFullscreen) { _exitFullscreen(); return false; }
        _saveProgress();
        return true;
      },
      child: Scaffold(
        backgroundColor: AppColors.bgBase,
        body: _isFullscreen ? _buildFullscreenPlayer() : _buildPortraitView(),
      ),
    );
  }

  // ─── Portrait View ─────────────────────────────────────────────────────────

  Widget _buildPortraitView() {
    final isTv = widget.season > 0;
    final hasNext = isTv && widget.totalEpisodes > 0 && widget.episode < widget.totalEpisodes;

    return Column(children: [
      SizedBox(height: MediaQuery.of(context).padding.top),

      // Player area
      AspectRatio(
        aspectRatio: 16 / 9,
        child: GestureDetector(
          onVerticalDragStart: _handleDragStart,
          onVerticalDragUpdate: _handleDragUpdate,
          onVerticalDragEnd: _handleDragEnd,
          onHorizontalDragStart: _handleDragStart,
          onHorizontalDragUpdate: _handleDragUpdate,
          onHorizontalDragEnd: _handleDragEnd,
          child: Stack(fit: StackFit.expand, children: [
            Container(color: Colors.black),
            // Video Player
            if (_controller != null && _controller!.value.isInitialized)
              Center(child: AspectRatio(
                aspectRatio: _controller!.value.aspectRatio,
                child: VideoPlayer(_controller!),
              )),
            
            // Buffering / Loading / Error
            if (_loadingResources || _initializingPlayer) _buildSmallLoading(),
            if (_hasError) _buildSmallError(),

            // Controls Overlay
            if (_controller != null && _controller!.value.isInitialized && !_hasError)
              _buildPortraitControls(hasNext),
            
            // Gesture HUD Indicator Overlay
            if (_hudType != null) _buildGestureHUD(),
          ]),
        ),
      ),

      // Details underneath
      Expanded(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(widget.title, style: GoogleFonts.outfit(fontSize: 17, fontWeight: FontWeight.w800, color: AppColors.textPrimary), maxLines: 2, overflow: TextOverflow.ellipsis),
            if (isTv) ...[
              const SizedBox(height: 4),
              Text('Season ${widget.season}  ·  Episode ${widget.episode}',
                style: GoogleFonts.outfit(fontSize: 13, color: AppColors.accent, fontWeight: FontWeight.w600)),
            ],
            const SizedBox(height: 14),

            // Language Selector Row
            if (_availableLanguages.length > 1) ...[
              _buildLanguageSelector(),
              const SizedBox(height: 14),
            ],

            // Player Options Chips
            if (_resources.isNotEmpty) ...[
              _buildOptionChips(),
              const SizedBox(height: 14),
            ],

            // Next Episode skip button
            if (hasNext)
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  onPressed: _nextEpisode,
                  icon: const Icon(Icons.skip_next_rounded, size: 20),
                  label: Text('Next Episode (E${widget.episode + 1})', style: GoogleFonts.outfit(fontWeight: FontWeight.w700, fontSize: 14)),
                  style: OutlinedButton.styleFrom(
                    foregroundColor: AppColors.accent, side: const BorderSide(color: AppColors.accent),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                    padding: const EdgeInsets.symmetric(vertical: 12),
                  ),
                ),
              ),
          ]),
        ),
      ),
    ]);
  }

  Widget _buildLanguageSelector() {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text('Audio Language', style: GoogleFonts.outfit(fontSize: 12, fontWeight: FontWeight.w700, color: AppColors.textMuted, letterSpacing: 0.8)),
      const SizedBox(height: 8),
      SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: Row(children: _availableLanguages.map((lang) {
          final isSel = (_selectedLanguage ?? _availableLanguages.first) == lang;
          return Padding(
            padding: const EdgeInsets.only(right: 8),
            child: GestureDetector(
              onTap: () {
                setState(() {
                  _selectedLanguage = lang;
                  final filtered = _resources.where((r) => (r.language ?? 'Original') == lang).toList();
                  if (filtered.isNotEmpty) {
                    _current = filtered.reduce((a, b) => a.resolution >= b.resolution ? a : b);
                    _initPlayer(_current!.resourceLink);
                  }
                });
              },
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                decoration: BoxDecoration(
                  color: isSel ? AppColors.accent : AppColors.bgOverlay,
                  borderRadius: BorderRadius.circular(50),
                  border: Border.all(color: isSel ? AppColors.accent : AppColors.border),
                ),
                child: Text(lang, style: GoogleFonts.outfit(color: isSel ? Colors.white : AppColors.textSecondary, fontWeight: isSel ? FontWeight.w700 : FontWeight.w500, fontSize: 13)),
              ),
            ),
          );
        }).toList()),
      ),
    ]);
  }

  Widget _buildOptionChips() {
    final qualityLabel = _current != null && _current!.resolution > 0 ? '${_current!.resolution}P' : 'Auto';
    return Row(children: [
      _buildChip(Icons.hd_rounded, qualityLabel, _resources.length > 1 ? _showQualitySheet : null),
      const SizedBox(width: 8),
      _buildChip(Icons.speed_rounded, '${_playbackSpeed}x', _showSpeedSheet),
      const SizedBox(width: 8),
      _buildChip(_fitFill ? Icons.fit_screen_rounded : Icons.crop_rounded, _fitFill ? 'Fill' : 'Fit', () => setState(() => _fitFill = !_fitFill)),
      const SizedBox(width: 8),
      // Gesture Controls Toggle
      _buildChip(_gesturesEnabled ? Icons.gesture_rounded : Icons.block_flipped, _gesturesEnabled ? 'Gestures On' : 'Gestures Off', () {
        setState(() => _gesturesEnabled = !_gesturesEnabled);
      }),
    ]);
  }

  Widget _buildChip(IconData icon, String label, VoidCallback? onTap) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
        decoration: BoxDecoration(
          color: AppColors.bgOverlay,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: AppColors.border),
        ),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          Icon(icon, color: AppColors.accent, size: 14),
          const SizedBox(width: 5),
          Text(label, style: GoogleFonts.outfit(color: AppColors.textPrimary, fontSize: 11, fontWeight: FontWeight.w700)),
        ]),
      ),
    );
  }

  Widget _buildPortraitControls(bool hasNext) {
    final ctrl = _controller!;
    return GestureDetector(
      onTap: _toggleControls,
      child: Container(
        color: Colors.transparent,
        child: AnimatedOpacity(
          opacity: _showControls ? 1.0 : 0.0,
          duration: const Duration(milliseconds: 200),
          child: IgnorePointer(
            ignoring: !_showControls,
            child: Container(
              decoration: const BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter, end: Alignment.bottomCenter,
                  colors: [Colors.black54, Colors.transparent, Colors.transparent, Colors.black54],
                  stops: [0.0, 0.3, 0.65, 1.0],
                ),
              ),
              child: Stack(children: [
                Positioned(
                  top: 4, left: 4,
                  child: IconButton(
                    onPressed: () { _saveProgress(); Navigator.pop(context); },
                    icon: const Icon(Icons.arrow_back_ios_new_rounded, color: Colors.white, size: 20),
                  ),
                ),
                Positioned(
                  top: 4, right: 4,
                  child: IconButton(
                    onPressed: _enterFullscreen,
                    icon: const Icon(Icons.fullscreen_rounded, color: Colors.white, size: 26),
                  ),
                ),
                // Center controls
                Center(child: Row(mainAxisSize: MainAxisSize.min, children: [
                  _buildCenterBtn(Icons.replay_10_rounded, () => _seek(-10)),
                  const SizedBox(width: 20),
                  ValueListenableBuilder<VideoPlayerValue>(
                    valueListenable: ctrl,
                    builder: (_, v, __) => GestureDetector(
                      onTap: _togglePlayPause,
                      child: Container(
                        width: 52, height: 52,
                        decoration: BoxDecoration(color: Colors.white.withValues(alpha: 0.2), shape: BoxShape.circle, border: Border.all(color: Colors.white60, width: 1.5)),
                        child: Icon(v.isPlaying ? Icons.pause_rounded : Icons.play_arrow_rounded, color: Colors.white, size: 30),
                      ),
                    ),
                  ),
                  const SizedBox(width: 20),
                  _buildCenterBtn(Icons.forward_10_rounded, () => _seek(10)),
                ])),
                // Bottom timeline
                Positioned(
                  bottom: 0, left: 0, right: 0,
                  child: ValueListenableBuilder<VideoPlayerValue>(
                    valueListenable: ctrl,
                    builder: (_, v, __) {
                      final pos = v.position; final dur = v.duration;
                      final progress = dur.inMilliseconds > 0 ? (pos.inMilliseconds / dur.inMilliseconds).clamp(0.0, 1.0) : 0.0;
                      return Padding(
                        padding: const EdgeInsets.fromLTRB(8, 0, 8, 4),
                        child: Row(children: [
                          Text(_fmt(pos), style: GoogleFonts.outfit(color: Colors.white, fontSize: 10, fontWeight: FontWeight.w600)),
                          Expanded(
                            child: SliderTheme(
                              data: SliderThemeData(
                                thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 5),
                                trackHeight: 2, overlayShape: const RoundSliderOverlayShape(overlayRadius: 10),
                                activeTrackColor: AppColors.accent, inactiveTrackColor: Colors.white30,
                                thumbColor: Colors.white, overlayColor: AppColors.accent.withValues(alpha: 0.2),
                              ),
                              child: Slider(
                                value: progress,
                                onChanged: (val) => ctrl.seekTo(Duration(milliseconds: (val * dur.inMilliseconds).round())),
                              ),
                            ),
                          ),
                          Text(_fmt(dur), style: GoogleFonts.outfit(color: Colors.white60, fontSize: 10)),
                        ]),
                      );
                    },
                  ),
                ),
              ]),
            ),
          ),
        ),
      ),
    );
  }

  // ─── Fullscreen View ───────────────────────────────────────────────────────

  Widget _buildFullscreenPlayer() {
    final ctrl = _controller;
    if (_loadingResources || _initializingPlayer || ctrl == null) {
      return Center(child: Column(mainAxisSize: MainAxisSize.min, children: [
        const SizedBox(width: 48, height: 48, child: CircularProgressIndicator(color: AppColors.accent, strokeWidth: 3)),
        const SizedBox(height: 16),
        Text(_loadingResources ? 'Loading stream...' : 'Initializing...', style: GoogleFonts.outfit(color: AppColors.textSecondary, fontSize: 14)),
      ]));
    }
    if (_hasError) return _buildError();

    final isTv = widget.season > 0;
    final hasNext = isTv && widget.totalEpisodes > 0 && widget.episode < widget.totalEpisodes;
    final qualityLabel = _current != null && _current!.resolution > 0 ? '${_current!.resolution}P' : 'AUTO';

    return GestureDetector(
      onVerticalDragStart: _handleDragStart,
      onVerticalDragUpdate: _handleDragUpdate,
      onVerticalDragEnd: _handleDragEnd,
      onHorizontalDragStart: _handleDragStart,
      onHorizontalDragUpdate: _handleDragUpdate,
      onHorizontalDragEnd: _handleDragEnd,
      onTap: _toggleControls,
      child: Stack(fit: StackFit.expand, children: [
        Container(color: Colors.black),
        // Video Render
        ValueListenableBuilder<VideoPlayerValue>(
          valueListenable: ctrl,
          builder: (_, v, __) {
            if (!v.isInitialized) return const SizedBox();
            return Center(
              child: AspectRatio(
                aspectRatio: v.aspectRatio,
                child: _fitFill
                    ? FittedBox(fit: BoxFit.fill, child: SizedBox(width: v.size.width, height: v.size.height, child: VideoPlayer(ctrl)))
                    : VideoPlayer(ctrl),
              ),
            );
          },
        ),

        // Buffering Indicator
        ValueListenableBuilder<VideoPlayerValue>(
          valueListenable: ctrl,
          builder: (_, v, __) => v.isBuffering
              ? const Center(child: SizedBox(width: 40, height: 40, child: CircularProgressIndicator(color: AppColors.accent, strokeWidth: 3)))
              : const SizedBox(),
        ),

        // Controls overlay
        AnimatedOpacity(
          opacity: _showControls ? 1.0 : 0.0,
          duration: const Duration(milliseconds: 200),
          child: IgnorePointer(
            ignoring: !_showControls,
            child: _isLocked ? _buildLockedOverlay() : _buildFullscreenControls(ctrl, hasNext, qualityLabel),
          ),
        ),

        // MX Drag Gestures HUD Overlay
        if (_hudType != null) _buildGestureHUD(),
      ]),
    );
  }

  Widget _buildLockedOverlay() {
    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter, end: Alignment.bottomCenter,
          colors: [Color(0xCC000000), Colors.transparent, Colors.transparent, Color(0xCC000000)],
          stops: [0.0, 0.2, 0.8, 1.0],
        ),
      ),
      child: Stack(children: [
        Positioned(
          left: 16, top: 0, bottom: 0,
          child: Center(
            child: GestureDetector(
              onTap: () { setState(() => _isLocked = false); _startHideTimer(); },
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
                decoration: BoxDecoration(color: Colors.black54, borderRadius: BorderRadius.circular(50)),
                child: Row(mainAxisSize: MainAxisSize.min, children: [
                  const Icon(Icons.lock_rounded, color: Colors.white, size: 18),
                  const SizedBox(width: 6),
                  Text('Tap to unlock', style: GoogleFonts.outfit(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w600)),
                ]),
              ),
            ),
          ),
        ),
      ]),
    );
  }

  Widget _buildFullscreenControls(VideoPlayerController ctrl, bool hasNext, String qualityLabel) {
    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter, end: Alignment.bottomCenter,
          colors: [Color(0xCC000000), Colors.transparent, Colors.transparent, Color(0xCC000000)],
          stops: [0.0, 0.25, 0.65, 1.0],
        ),
      ),
      child: Stack(children: [
        // TOP BAR
        Positioned(top: 0, left: 0, right: 0,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(4, 4, 4, 0),
            child: Row(children: [
              IconButton(
                onPressed: _exitFullscreen,
                icon: const Icon(Icons.fullscreen_exit_rounded, color: Colors.white, size: 24),
              ),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(widget.title, style: GoogleFonts.outfit(color: Colors.white, fontSize: 14, fontWeight: FontWeight.w700), maxLines: 1, overflow: TextOverflow.ellipsis),
                  if (widget.season > 0)
                    Text('S${widget.season.toString().padLeft(2,'0')} E${widget.episode.toString().padLeft(2,'0')}',
                      style: GoogleFonts.outfit(color: AppColors.accent, fontSize: 11, fontWeight: FontWeight.w600)),
                ]),
              ),
              IconButton(
                onPressed: () {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Swipe left-side for Volume / right-side for Brightness / horizontal to Seek'), duration: Duration(seconds: 3)),
                  );
                },
                icon: const Icon(Icons.help_outline_rounded, color: Colors.white, size: 20),
              ),
              IconButton(
                onPressed: _resources.length > 1 ? _showQualitySheet : null,
                icon: const Icon(Icons.settings_rounded, color: Colors.white, size: 22),
              ),
            ]),
          ),
        ),

        // LOCK BUTTON (left center)
        Positioned(left: 16, top: 0, bottom: 60,
          child: Center(
            child: GestureDetector(
              onTap: () { setState(() { _isLocked = true; _showControls = true; }); },
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                decoration: BoxDecoration(color: Colors.black54, borderRadius: BorderRadius.circular(50)),
                child: Row(mainAxisSize: MainAxisSize.min, children: [
                  const Icon(Icons.lock_open_rounded, color: Colors.white, size: 16),
                  const SizedBox(width: 6),
                  Text('Tap to Lock', style: GoogleFonts.outfit(color: Colors.white, fontSize: 11, fontWeight: FontWeight.w600)),
                ]),
              ),
            ),
          ),
        ),

        // CENTER
        Center(child: Row(mainAxisSize: MainAxisSize.min, children: [
          _buildCenterBtn(Icons.replay_10_rounded, () => _seek(-10)),
          const SizedBox(width: 28),
          ValueListenableBuilder<VideoPlayerValue>(
            valueListenable: ctrl,
            builder: (_, v, __) => GestureDetector(
              onTap: _togglePlayPause,
              child: Container(
                width: 64, height: 64,
                decoration: BoxDecoration(color: Colors.white.withValues(alpha: 0.2), shape: BoxShape.circle, border: Border.all(color: Colors.white60, width: 2)),
                child: Icon(v.isPlaying ? Icons.pause_rounded : Icons.play_arrow_rounded, color: Colors.white, size: 36),
              ),
            ),
          ),
          const SizedBox(width: 28),
          _buildCenterBtn(Icons.forward_10_rounded, () => _seek(10)),
        ])),

        // BOTTOM BAR
        Positioned(bottom: 0, left: 0, right: 0,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              ValueListenableBuilder<VideoPlayerValue>(
                valueListenable: ctrl,
                builder: (_, v, __) {
                  final pos = v.position; final dur = v.duration;
                  final progress = dur.inMilliseconds > 0 ? (pos.inMilliseconds / dur.inMilliseconds).clamp(0.0, 1.0) : 0.0;
                  return Row(children: [
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
                          onChangeStart: (_) => _hideTimer?.cancel(),
                          onChangeEnd: (_) => _startHideTimer(),
                        ),
                      ),
                    ),
                    Text(_fmt(dur), style: GoogleFonts.outfit(color: Colors.white60, fontSize: 11)),
                  ]);
                },
              ),
              Row(children: [
                ValueListenableBuilder<VideoPlayerValue>(
                  valueListenable: ctrl,
                  builder: (_, v, __) => IconButton(
                    onPressed: _togglePlayPause, padding: EdgeInsets.zero, constraints: const BoxConstraints(),
                    icon: Icon(v.isPlaying ? Icons.pause_rounded : Icons.play_arrow_rounded, color: Colors.white, size: 26),
                  ),
                ),
                const SizedBox(width: 4),
                if (hasNext)
                  IconButton(
                    onPressed: _nextEpisode, padding: EdgeInsets.zero, constraints: const BoxConstraints(),
                    icon: const Icon(Icons.skip_next_rounded, color: Colors.white, size: 26),
                  ),
                const Spacer(),
                _buildTextBtn(_fitFill ? 'Fill' : 'Fit', () => setState(() => _fitFill = !_fitFill)),
                const SizedBox(width: 8),
                _buildTextBtn('${_playbackSpeed == 1.0 ? '1' : _playbackSpeed}x', _showSpeedSheet),
                const SizedBox(width: 8),
                // Language
                if (_availableLanguages.isNotEmpty) ...[
                  _buildTextBtn('Language', _showLanguageSheet),
                  const SizedBox(width: 8),
                ],
                // Quality
                _buildTextBtn(qualityLabel, _showQualitySheet),
                const SizedBox(width: 8),
                // Gesture toggle in controls
                IconButton(
                  onPressed: () { setState(() => _gesturesEnabled = !_gesturesEnabled); },
                  padding: EdgeInsets.zero, constraints: const BoxConstraints(),
                  icon: Icon(_gesturesEnabled ? Icons.gesture_rounded : Icons.block_flipped, color: Colors.white, size: 20),
                ),
              ]),
            ]),
          ),
        ),
      ]),
    );
  }

  // ─── Gesture HUD Overlay widget ─────────────────────────────────────────────

  Widget _buildGestureHUD() {
    IconData iconData;
    Color color = AppColors.accent;

    if (_hudType == 'volume') {
      iconData = _boostVolumeValue > 0 ? Icons.volume_up_rounded : Icons.volume_down_rounded;
      if (_boostVolumeValue > 0) color = const Color(0xFF00D4AA); // Boost color
    } else if (_hudType == 'brightness') {
      iconData = Icons.brightness_medium_rounded;
    } else {
      iconData = Icons.compare_arrows_rounded;
    }

    return Positioned(
      top: 60,
      left: 0, right: 0,
      child: Center(
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
          decoration: BoxDecoration(
            color: Colors.black.withValues(alpha: 0.75),
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: color.withValues(alpha: 0.5), width: 1.5),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(iconData, color: color, size: 24),
                  const SizedBox(width: 10),
                  Text(
                    _hudText,
                    style: GoogleFonts.outfit(
                      color: Colors.white,
                      fontSize: 14,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                ],
              ),
              if (_hudType != 'seek') ...[
                const SizedBox(height: 8),
                SizedBox(
                  width: 150,
                  height: 4,
                  child: LinearProgressIndicator(
                    value: _hudProgress,
                    backgroundColor: Colors.white24,
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

  // ─── Small Error/Loading Helpers ───────────────────────────────────────────

  Widget _buildSmallLoading() => Container(
    color: Colors.black,
    child: Center(child: Column(mainAxisSize: MainAxisSize.min, children: [
      const SizedBox(width: 32, height: 32, child: CircularProgressIndicator(color: AppColors.accent, strokeWidth: 2.5)),
      const SizedBox(height: 10),
      Text(_loadingResources ? 'Loading stream...' : 'Initializing...',
        style: GoogleFonts.outfit(color: AppColors.textSecondary, fontSize: 12)),
    ])),
  );

  Widget _buildSmallError() => Container(
    color: Colors.black,
    padding: const EdgeInsets.all(16),
    child: Center(child: Column(mainAxisSize: MainAxisSize.min, children: [
      const Icon(Icons.error_outline_rounded, color: AppColors.accent, size: 32),
      const SizedBox(height: 8),
      Text(_errorMsg, textAlign: TextAlign.center, style: GoogleFonts.outfit(color: AppColors.textSecondary, fontSize: 12), maxLines: 3),
      const SizedBox(height: 10),
      ElevatedButton.icon(
        onPressed: _loadResources,
        icon: const Icon(Icons.refresh_rounded, size: 16),
        label: const Text('Retry'),
        style: ElevatedButton.styleFrom(backgroundColor: AppColors.accent, foregroundColor: Colors.white, padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8), shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(50))),
      ),
    ])),
  );

  Widget _buildError() => Center(
    child: Padding(
      padding: const EdgeInsets.all(32),
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        Container(padding: const EdgeInsets.all(20), decoration: BoxDecoration(color: AppColors.accent.withValues(alpha: 0.1), shape: BoxShape.circle),
          child: const Icon(Icons.signal_cellular_connected_no_internet_4_bar_rounded, color: AppColors.accent, size: 48)),
        const SizedBox(height: 20),
        Text(_errorMsg, textAlign: TextAlign.center, style: GoogleFonts.plusJakartaSans(color: AppColors.textSecondary, fontSize: 14, height: 1.6)),
        const SizedBox(height: 28),
        Row(mainAxisAlignment: MainAxisAlignment.center, children: [
          OutlinedButton.icon(onPressed: () { _saveProgress(); Navigator.pop(context); },
            icon: const Icon(Icons.arrow_back_ios_new_rounded, size: 14), label: const Text('Go Back'),
            style: OutlinedButton.styleFrom(foregroundColor: AppColors.textSecondary, side: const BorderSide(color: AppColors.border), shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(50)), padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12))),
          const SizedBox(width: 12),
          ElevatedButton.icon(onPressed: _loadResources,
            icon: const Icon(Icons.refresh_rounded, size: 18), label: const Text('Retry'),
            style: ElevatedButton.styleFrom(backgroundColor: AppColors.accent, foregroundColor: Colors.white, shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(50)), padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12))),
        ]),
      ]),
    ),
  );

  // ─── Helpers ───────────────────────────────────────────────────────────────

  Widget _buildCenterBtn(IconData icon, VoidCallback onTap) => GestureDetector(
    onTap: onTap,
    child: Container(
      width: 46, height: 46,
      decoration: const BoxDecoration(color: Colors.black38, shape: BoxShape.circle),
      child: Icon(icon, color: Colors.white, size: 28),
    ),
  );

  Widget _buildTextBtn(String label, VoidCallback onTap) => GestureDetector(
    onTap: onTap,
    child: Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(color: Colors.white.withValues(alpha: 0.15), borderRadius: BorderRadius.circular(6), border: Border.all(color: Colors.white.withValues(alpha: 0.3))),
      child: Text(label, style: GoogleFonts.outfit(color: Colors.white, fontSize: 11, fontWeight: FontWeight.w700)),
    ),
  );

  Widget _buildSeekBubble(bool forward) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
    decoration: BoxDecoration(color: Colors.black.withValues(alpha: 0.55), borderRadius: BorderRadius.circular(50)),
    child: Column(mainAxisSize: MainAxisSize.min, children: [
      Icon(forward ? Icons.fast_forward_rounded : Icons.fast_rewind_rounded, color: Colors.white, size: 26),
      const SizedBox(height: 2),
      Text(forward ? '+10s' : '-10s', style: GoogleFonts.outfit(color: Colors.white, fontSize: 11, fontWeight: FontWeight.w700)),
    ]),
  );

  String _fmt(Duration d) {
    final h = d.inHours;
    final m = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    return h > 0 ? '$h:$m:$s' : '$m:$s';
  }
}
