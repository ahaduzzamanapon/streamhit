import 'dart:async';
import 'dart:io';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:video_player/video_player.dart';
import 'package:screen_brightness/screen_brightness.dart';
import 'package:flutter_volume_controller/flutter_volume_controller.dart';
import 'package:provider/provider.dart';
import 'package:floating/floating.dart';
import '../core/theme.dart';
import '../core/api_service.dart';
import '../core/caching_proxy.dart';
import '../core/subtitle_parser.dart';
import '../core/constants.dart';
import '../models/subject.dart';
import '../models/episode.dart';
import '../models/live_tv_channel.dart';
import 'details_screen.dart';
import 'downloads_screen.dart';
import '../widgets/search_header.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../providers/downloads_provider.dart';
import '../providers/continue_watching_provider.dart';

enum VideoScaleMode { fit, zoom, stretch }

class WatchScreen extends StatefulWidget {
  final Subject subject;
  final int? season;
  final int? episode;
  final bool isLive;
  final List<LiveStreamLink>? liveLinks;
  final int? liveIndex;

  const WatchScreen({
    super.key,
    required this.subject,
    this.season,
    this.episode,
    this.isLive = false,
    this.liveLinks,
    this.liveIndex,
  });

  @override
  State<WatchScreen> createState() => _WatchScreenState();
}

class _WatchScreenState extends State<WatchScreen> {
  VideoPlayerController? _controller;
  final Floating _floating = Floating();
  StreamSubscription<PiPStatus>? _pipSubscription;
  bool _isPipSupported = false;
  bool _isInPipMode = false;
  
  // UI State
  bool _isLoading = true;
  bool _showControls = true;
  Timer? _controlsTimer;
  bool _isInWatchlist = false;

  // Stream Info
  List<PlayResource> _resources = [];
  PlayResource? _selectedResource;
  List<SubtitleCaption> _captions = [];
  SubtitleCaption? _selectedCaption;
  List<SubtitleEntry> _subtitleEntries = [];
  String _currentSubtitleText = '';

  // TV Info
  int? _currentSeason;
  int? _currentEpisode;
  List<SeasonInfo> _seasons = [];

  // Gesture variables
  double _brightnessValue = 0.5;
  double _volumeValue = 0.5; // ranges 0.0 to 2.0 (representing 200%)
  bool _showVolumeIndicator = false;
  bool _showBrightnessIndicator = false;
  bool _showSeekIndicator = false;
  
  // Gesture seek helpers
  double _dragSeekTarget = 0.0;
  double _dragSeekStart = 0.0;

  // Drag seek bar state variables
  bool _isDragging = false;
  double _dragValue = 0.0;

  // Next Episode Countdown
  bool _showNextCountdown = false;
  int _countdownSeconds = 10;
  Timer? _countdownTimer;
  Timer? _bufferingTimeoutTimer;

  bool _isFullscreen = false;
  List<Subject> _recommendations = [];
  int _currentInitId = 0;
  int _lastSavedSeconds = -1;
  int _currentLiveIndex = 0;
  bool _isLocked = false;
  bool _showLockIcon = false;
  Timer? _lockIconTimer;
  bool _isBuffering = false;
  VideoScaleMode _scaleMode = VideoScaleMode.fit;
  double _playbackSpeed = 1.0;
  String? _errorMessage;
  String? _currentUrl;
  bool _currentIsLocal = false;
  
  // Retry state variables
  int _retryCount = 0;
  bool _isRetrying = false;
  static const int _maxRetries = 10;
  Duration? _errorResumePosition;

  @override
  void initState() {
    super.initState();
    final isTv = widget.subject.seasonCount > 0 || widget.subject.subjectType == 2;
    _currentSeason = widget.season ?? (isTv ? 1 : null);
    _currentEpisode = widget.episode ?? (isTv ? 1 : null);

    // Default to portrait for video page details
    SystemChrome.setPreferredOrientations([
      DeviceOrientation.portraitUp,
    ]);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);

    _checkPipSupport();
    _loadStreamInfo();
    _loadRecommendations();

    // Listen to PiP status changes
    _pipSubscription = _floating.pipStatusStream.listen((status) {
      if (mounted) {
        setState(() {
          _isInPipMode = status == PiPStatus.enabled;
        });
      }
    });
  }

  @override
  void dispose() {
    CachingProxyServer().cancelActivePreBuffering();
    // Reset Orientation to normal portrait
    SystemChrome.setPreferredOrientations([
      DeviceOrientation.portraitUp,
    ]);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);

    _controlsTimer?.cancel();
    _countdownTimer?.cancel();
    _bufferingTimeoutTimer?.cancel();
    _pipSubscription?.cancel();
    _lockIconTimer?.cancel();
    _controller?.removeListener(_onControllerUpdate);
    _controller?.dispose();
    super.dispose();
  }

  Future<void> _checkPipSupport() async {
    final supported = await _floating.isPipAvailable;
    if (!mounted) return;
    setState(() {
      _isPipSupported = supported;
    });
  }

  Future<void> _loadRecommendations() async {
    final firstGenre = widget.subject.genres.isNotEmpty ? widget.subject.genres[0] : '*';
    final recRes = await ApiService.fetchFilteredData(
      genre: firstGenre,
      subjectType: widget.subject.subjectType,
      page: 1,
    );
    if (recRes != null && recRes['items'] != null) {
      final List list = recRes['items'];
      if (!mounted) return;
      setState(() {
        _recommendations = list
            .map((x) => Subject.fromJson(x))
            .where((x) => x.subjectId != widget.subject.subjectId)
            .take(6)
            .toList();
      });
    }
  }

  void _toggleFullscreen() {
    setState(() {
      _isFullscreen = !_isFullscreen;
    });

    if (_isFullscreen) {
      SystemChrome.setPreferredOrientations([
        DeviceOrientation.landscapeLeft,
        DeviceOrientation.landscapeRight,
      ]);
      SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    } else {
      SystemChrome.setPreferredOrientations([
        DeviceOrientation.portraitUp,
      ]);
      SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    }
  }

  Future<void> _loadStreamInfo() async {
    setState(() {
      _isLoading = true;
      _showNextCountdown = false;
    });

    _countdownTimer?.cancel();

    if (widget.isLive && widget.liveLinks != null && widget.liveLinks!.isNotEmpty) {
      final idx = widget.liveIndex ?? 0;
      _currentLiveIndex = idx;
      final currentLinkObj = widget.liveLinks![idx];
      
      final encodedUrl = Uri.encodeComponent(currentLinkObj.url);
      final encodedRef = Uri.encodeComponent(currentLinkObj.referer);
      final encodedOrig = Uri.encodeComponent(currentLinkObj.origin);
      final encodedUA = Uri.encodeComponent(currentLinkObj.userAgent);
      final useProxy = currentLinkObj.useBdProxy ? 'true' : 'false';
      
      final proxiedUrl = '${Constants.baseUrl}/api/sports/proxy?url=$encodedUrl&referer=$encodedRef&origin=$encodedOrig&userAgent=$encodedUA&use_bd_proxy=$useProxy';
      
      _initializePlayer(proxiedUrl);
      return;
    }

    // Check if item is offline (downloaded)
    final dlProvider = Provider.of<DownloadsProvider>(context, listen: false);
    final downloadedItem = dlProvider.getDownload(
      widget.subject.subjectId,
      season: _currentSeason ?? 0,
      episode: _currentEpisode ?? 0,
    );

    if (downloadedItem != null && downloadedItem.status == 'completed') {
      print('Playing local downloaded file: ${downloadedItem.localPath}');
      _initializePlayer(Uri.parse(downloadedItem.localPath).toString(), isLocal: true);
      
      setState(() {
        _isLoading = false;
      });
      _loadStreamInfoBackground();
      return;
    }

    // Otherwise, fetch online stream resources
    final isTv = widget.subject.seasonCount > 0 || widget.subject.subjectType == 2;
    final resources = await ApiService.fetchPlayResources(
      widget.subject.subjectId,
      detailPath: widget.subject.detailPath,
      se: isTv ? _currentSeason : null,
      ep: isTv ? _currentEpisode : null,
    );

    if (!mounted) return;

    if (resources.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('No streaming links available for this media.')),
      );
      Navigator.pop(context);
      return;
    }

    // Auto-select highest resolution (Auto / default)
    PlayResource bestResource = resources.reduce((a, b) => a.resolution > b.resolution ? a : b);
    
    // Fetch captions/subtitles in background
    final captions = await ApiService.fetchCaptions(widget.subject.subjectId, bestResource.resourceId);

    setState(() {
      _resources = resources;
      _selectedResource = bestResource;
      _captions = captions;
      _selectedCaption = null; // Subtitles off by default
    });

    // Translate URL via Local Caching Proxy
    final proxyUrl = CachingProxyServer().getProxyUrl(bestResource.resourceLink);
    _initializePlayer(proxyUrl);

    // Fetch TV season episodes list for TV shows next-episode autoplay logic
    if (isTv && _seasons.isEmpty) {
      final seasons = await ApiService.fetchSeasonInfo(widget.subject.subjectId, widget.subject.detailPath);
      if (!mounted) return;
      setState(() {
        _seasons = seasons;
      });
    }

    // Load subtitles if selected
    if (_selectedCaption != null) {
      _loadSubtitles(_selectedCaption!.src);
    }
  }

  Future<void> _loadSubtitles(String url) async {
    final entries = await SubtitleParser.parseFromUrl(url);
    if (!mounted) return;
    setState(() {
      _subtitleEntries = entries;
    });
  }

  void _initializePlayer(String url, {bool isLocal = false}) async {
    _currentUrl = url;
    _currentIsLocal = isLocal;
    CachingProxyServer().cancelActivePreBuffering();
    final int initId = ++_currentInitId;
    final oldController = _controller;
    
    // Clear the active controller synchronously so the widget doesn't render it while disposing
    oldController?.removeListener(_onControllerUpdate);
    
    setState(() {
      _controller = null;
      _isLoading = true;
      _errorMessage = null;
    });
    _startBufferingTimeout();

    if (oldController != null) {
      try {
        await oldController.dispose();
      } catch (e) {
        print('Error disposing old controller: $e');
      }
    }

    if (initId != _currentInitId) return;

    final newController = isLocal
        ? VideoPlayerController.file(File(url))
        : VideoPlayerController.networkUrl(Uri.parse(url));

    _controller = newController;

    try {
      await newController.initialize();
      
      if (initId != _currentInitId) {
        newController.dispose();
        return;
      }

      newController.addListener(_onControllerUpdate);

      if (!mounted) return;
      
      final resumePos = _errorResumePosition;
      if (resumePos != null) {
        newController.seekTo(resumePos);
        _errorResumePosition = null; // Reset
      } else {
        // Check for saved progress (Resume toast)
        final historyProvider = Provider.of<ContinueWatchingProvider>(context, listen: false);
        final savedProgress = await historyProvider.getSavedProgress(
          widget.subject.subjectId,
          season: _currentSeason ?? 0,
          episode: _currentEpisode ?? 0,
        );

        if (savedProgress > 5 && savedProgress < newController.value.duration.inSeconds - 10) {
          newController.seekTo(Duration(seconds: savedProgress.round()));
          _showToastMessage('Resumed from ${Duration(seconds: savedProgress.round()).toString().split('.').first}');
        }
      }

      await newController.play();
      
      // Load current volume/brightness levels
      _brightnessValue = await ScreenBrightness().current;
      _volumeValue = (await FlutterVolumeController.getVolume()) ?? 0.5;

      if (!mounted) return;
      setState(() {
        _isLoading = false;
        _retryCount = 0; // Reset retry count on successful play
        _isRetrying = false;
      });
      _clearBufferingTimeout();
      _startControlsAutoHide();
    } catch (e) {
      print('Video player initialization error: $e');
      if (initId != _currentInitId) return;
      
      if (!mounted) return;
      
      if (widget.isLive && widget.liveLinks != null && _currentLiveIndex < widget.liveLinks!.length - 1) {
        _handleLivePlaybackFallback();
        return;
      }
      
      if (_retryCount < _maxRetries) {
        _retryCount++;
        setState(() {
          _isRetrying = true;
          _isLoading = true;
          _errorMessage = null; // Keep it null to show retry description under spinner
        });
        print('Retrying player initialization ($_retryCount/$_maxRetries) in 2 seconds...');
        await Future.delayed(const Duration(seconds: 2));
        if (initId == _currentInitId && mounted) {
          _initializePlayer(url, isLocal: isLocal);
        }
        return;
      }
      
      setState(() {
        _errorMessage = 'Failed to load video: $e';
        _isLoading = false;
        _isRetrying = false;
      });
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Failed to load video stream: $e')),
      );
    }
  }

  void _onControllerUpdate() {
    if (!mounted || _controller == null) return;

    if (_controller!.value.hasError) {
      final errorMsg = _controller!.value.errorDescription ?? 'Playback error';
      print('Controller playback error detected: $errorMsg');
      
      if (mounted) {
        if (widget.isLive && widget.liveLinks != null && _currentLiveIndex < widget.liveLinks!.length - 1) {
          _handleLivePlaybackFallback();
          return;
        }

        if (_retryCount < _maxRetries) {
          _retryCount++;
          final lastPosition = _controller!.value.position;
          _errorResumePosition = lastPosition;
          
          setState(() {
            _isRetrying = true;
            _isLoading = true;
            _errorMessage = null; // Keep it null to show retry text under spinner
          });
          
          final currentUrl = _currentUrl;
          final currentIsLocal = _currentIsLocal;
          final initId = _currentInitId;
          
          print('Auto-retrying playback from position $lastPosition in 2 seconds...');
          Future.delayed(const Duration(seconds: 2), () {
            if (mounted && initId == _currentInitId && currentUrl != null) {
              _initializePlayer(currentUrl, isLocal: currentIsLocal);
            }
          });
          return;
        }
        
        if (_errorMessage != errorMsg) {
          setState(() {
            _errorMessage = errorMsg;
            _isLoading = false;
            _isRetrying = false;
          });
        }
      }
      return;
    }
    
    final isBufferingNow = _controller!.value.isBuffering;
    if (isBufferingNow != _isBuffering) {
      setState(() {
        _isBuffering = isBufferingNow;
      });
    }
    
    if (_isLoading || isBufferingNow) {
      if (_bufferingTimeoutTimer == null) {
        _startBufferingTimeout();
      }
    } else {
      _clearBufferingTimeout();
    }
    
    // Save progress to watch history once per second to reduce IO/state writes
    if (_controller!.value.isPlaying) {
      final positionSec = _controller!.value.position.inSeconds;
      if (positionSec != _lastSavedSeconds) {
        _lastSavedSeconds = positionSec;
        Provider.of<ContinueWatchingProvider>(context, listen: false).saveProgress(
          widget.subject,
          season: _currentSeason ?? 0,
          episode: _currentEpisode ?? 0,
          position: positionSec.toDouble(),
          duration: _controller!.value.duration.inSeconds.toDouble(),
        );
      }
    }

    // Sync subtitles
    final currentPos = _controller!.value.position;
    String subText = '';
    for (final entry in _subtitleEntries) {
      if (currentPos >= entry.start && currentPos <= entry.end) {
        subText = entry.text;
        break;
      }
    }
    if (subText != _currentSubtitleText) {
      setState(() {
        _currentSubtitleText = subText;
      });
    }

    // Netflix Countdown auto-play next episode
    final isTv = widget.subject.seasonCount > 0 || widget.subject.subjectType == 2;
    if (isTv && _controller!.value.position >= _controller!.value.duration - const Duration(seconds: 10)) {
      if (!_showNextCountdown && _hasNextEpisode()) {
        _triggerNextEpisodeCountdown();
      }
    } else {
      if (_showNextCountdown) {
        _countdownTimer?.cancel();
        setState(() {
          _showNextCountdown = false;
        });
      }
    }
  }

  void _startBufferingTimeout() {
    _clearBufferingTimeout();
    if (widget.isLive && widget.liveLinks != null && widget.liveLinks!.isNotEmpty) {
      print('[Buffering Timeout] Started 10s timer...');
      _bufferingTimeoutTimer = Timer(const Duration(seconds: 10), () {
        print('[Buffering Timeout] 10 seconds reached. Falling back to next stream link...');
        _handleLivePlaybackFallback();
      });
    }
  }

  void _clearBufferingTimeout() {
    _bufferingTimeoutTimer?.cancel();
    _bufferingTimeoutTimer = null;
  }

  void _handleLivePlaybackFallback() {
    if (widget.isLive && widget.liveLinks != null && _currentLiveIndex < widget.liveLinks!.length - 1) {
      _currentLiveIndex++;
      final nextLinkObj = widget.liveLinks![_currentLiveIndex];
      print('[Live Fallback] Stream failed. Trying backup Link ${_currentLiveIndex + 1}: ${nextLinkObj.label}');
      
      // Clean up controller to prepare for fallback
      _controller?.removeListener(_onControllerUpdate);
      final oldController = _controller;
      _controller = null;
      if (oldController != null) {
        oldController.dispose();
      }

      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Stream offline. Trying backup: ${nextLinkObj.label}'),
            backgroundColor: AppTheme.accentColor,
            duration: const Duration(seconds: 3),
          ),
        );
      });
      
      final encodedUrl = Uri.encodeComponent(nextLinkObj.url);
      final encodedRef = Uri.encodeComponent(nextLinkObj.referer);
      final encodedOrig = Uri.encodeComponent(nextLinkObj.origin);
      final encodedUA = Uri.encodeComponent(nextLinkObj.userAgent);
      final useProxy = nextLinkObj.useBdProxy ? 'true' : 'false';
      
      final proxiedUrl = '${Constants.baseUrl}/api/sports/proxy?url=$encodedUrl&referer=$encodedRef&origin=$encodedOrig&userAgent=$encodedUA&use_bd_proxy=$useProxy';
      
      _initializePlayer(proxiedUrl);
    }
  }

  bool _hasNextEpisode() {
    if (_seasons.isEmpty || _currentSeason == null || _currentEpisode == null) return false;
    final seasonObj = _seasons.firstWhere((s) => s.seasonNumber == _currentSeason, orElse: () => _seasons[0]);
    final epIdx = seasonObj.episodes.indexOf(_currentEpisode!);
    
    if (epIdx != -1 && epIdx < seasonObj.episodes.length - 1) {
      return true; // Next episode in same season
    }
    
    // Check if next season exists
    final nextSeasonNum = _currentSeason! + 1;
    return _seasons.any((s) => s.seasonNumber == nextSeasonNum);
  }

  bool _hasPreviousEpisode() {
    if (_seasons.isEmpty || _currentSeason == null || _currentEpisode == null) return false;
    final seasonObj = _seasons.firstWhere((s) => s.seasonNumber == _currentSeason, orElse: () => _seasons[0]);
    final epIdx = seasonObj.episodes.indexOf(_currentEpisode!);
    
    if (epIdx > 0) {
      return true; // Prev episode in same season
    }
    
    // Check if previous season exists
    final prevSeasonNum = _currentSeason! - 1;
    return _seasons.any((s) => s.seasonNumber == prevSeasonNum);
  }

  void _triggerNextEpisodeCountdown() {
    setState(() {
      _showNextCountdown = true;
      _countdownSeconds = 10;
    });

    _countdownTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (!mounted) return;
      setState(() {
        _countdownSeconds--;
      });

      if (_countdownSeconds <= 0) {
        timer.cancel();
        _playNextEpisode();
      }
    });
  }

  void _playNextEpisode() {
    if (_seasons.isEmpty || _currentSeason == null || _currentEpisode == null) return;
    final seasonObj = _seasons.firstWhere((s) => s.seasonNumber == _currentSeason, orElse: () => _seasons[0]);
    final epIdx = seasonObj.episodes.indexOf(_currentEpisode!);
    
    if (epIdx != -1 && epIdx < seasonObj.episodes.length - 1) {
      // Switch episode
      _currentEpisode = seasonObj.episodes[epIdx + 1];
    } else {
      // Switch season
      final nextSeasonNum = _currentSeason! + 1;
      final nextSeasonObj = _seasons.firstWhere((s) => s.seasonNumber == nextSeasonNum);
      _currentSeason = nextSeasonNum;
      _currentEpisode = nextSeasonObj.episodes.isNotEmpty ? nextSeasonObj.episodes[0] : 1;
    }
    
    _loadStreamInfo();
  }

  void _playPreviousEpisode() {
    if (_seasons.isEmpty || _currentSeason == null || _currentEpisode == null) return;
    final seasonObj = _seasons.firstWhere((s) => s.seasonNumber == _currentSeason, orElse: () => _seasons[0]);
    final epIdx = seasonObj.episodes.indexOf(_currentEpisode!);
    
    if (epIdx > 0) {
      // Switch episode
      _currentEpisode = seasonObj.episodes[epIdx - 1];
    } else {
      // Switch season
      final prevSeasonNum = _currentSeason! - 1;
      final prevSeasonObj = _seasons.firstWhere((s) => s.seasonNumber == prevSeasonNum, orElse: () => _seasons[0]);
      _currentSeason = prevSeasonNum;
      _currentEpisode = prevSeasonObj.episodes.isNotEmpty ? prevSeasonObj.episodes.last : 1;
    }
    
    _loadStreamInfo();
  }

  void _startControlsAutoHide() {
    _controlsTimer?.cancel();
    _controlsTimer = Timer(const Duration(seconds: 5), () {
      if (mounted) {
        setState(() {
          _showControls = false;
        });
      }
    });
  }

  void _toggleControls() {
    setState(() {
      _showControls = !_showControls;
    });
    if (_showControls) {
      _startControlsAutoHide();
    } else {
      _controlsTimer?.cancel();
    }
  }

  void _showToastMessage(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(msg),
        duration: const Duration(seconds: 2),
        behavior: SnackBarBehavior.floating,
        backgroundColor: AppTheme.cardColor,
      ),
    );
  }

  void _toggleScaleMode() {
    setState(() {
      switch (_scaleMode) {
        case VideoScaleMode.fit:
          _scaleMode = VideoScaleMode.zoom;
          _showToastMessage('Scale Mode: Zoom (Fill Screen)');
          break;
        case VideoScaleMode.zoom:
          _scaleMode = VideoScaleMode.stretch;
          _showToastMessage('Scale Mode: Stretch');
          break;
        case VideoScaleMode.stretch:
          _scaleMode = VideoScaleMode.fit;
          _showToastMessage('Scale Mode: Fit (Original)');
          break;
      }
    });
    _startControlsAutoHide();
  }

  void _toggleSpeed() {
    double nextSpeed = 1.0;
    if (_playbackSpeed == 1.0) {
      nextSpeed = 1.25;
    } else if (_playbackSpeed == 1.25) {
      nextSpeed = 1.5;
    } else if (_playbackSpeed == 1.5) {
      nextSpeed = 2.0;
    } else if (_playbackSpeed == 2.0) {
      nextSpeed = 0.75;
    } else if (_playbackSpeed == 0.75) {
      nextSpeed = 1.0;
    }
    
    setState(() {
      _playbackSpeed = nextSpeed;
    });
    _controller?.setPlaybackSpeed(nextSpeed);
    _showToastMessage('Playback Speed: ${nextSpeed}x');
    _startControlsAutoHide();
  }

  void _showHelpDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: AppTheme.surfaceColor,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: const Row(
          children: [
            Icon(Icons.help_outline_rounded, color: AppTheme.accentColor),
            SizedBox(width: 10),
            Text('Player Gestures', style: TextStyle(fontFamily: 'Outfit', color: Colors.white, fontSize: 18, fontWeight: FontWeight.bold)),
          ],
        ),
        content: const Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('• Swipe Left side vertically to adjust Brightness.', style: TextStyle(color: Colors.white70, fontSize: 13, height: 1.4)),
            SizedBox(height: 8),
            Text('• Swipe Right side vertically to adjust Volume.', style: TextStyle(color: Colors.white70, fontSize: 13, height: 1.4)),
            SizedBox(height: 8),
            Text('• Swipe horizontally or use Center icons to Seek.', style: TextStyle(color: Colors.white70, fontSize: 13, height: 1.4)),
            SizedBox(height: 8),
            Text('• Use Left-Center lock button to prevent accidental touches.', style: TextStyle(color: Colors.white70, fontSize: 13, height: 1.4)),
          ],
        ),
        actions: [
          TextButton(
            child: const Text('Got it', style: TextStyle(color: AppTheme.accentColor, fontWeight: FontWeight.bold)),
            onPressed: () => Navigator.pop(context),
          ),
        ],
      ),
    );
  }

  void _showLanguageSelector() {
    if (widget.subject.dubs.isEmpty) {
      _showToastMessage('Only standard audio available.');
      return;
    }
    
    showModalBottomSheet(
      context: context,
      backgroundColor: AppTheme.surfaceColor,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (context) {
        return SafeArea(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 16),
                child: Text(
                  'Select Audio Language',
                  style: TextStyle(
                    fontFamily: 'Outfit',
                    fontSize: 16,
                    fontWeight: FontWeight.bold,
                    color: Colors.white,
                  ),
                ),
              ),
              const Divider(color: Colors.white12),
              Flexible(
                child: ListView.builder(
                  shrinkWrap: true,
                  itemCount: widget.subject.dubs.length,
                  itemBuilder: (context, index) {
                    final dub = widget.subject.dubs[index];
                    final isCurrent = dub.subjectId == widget.subject.subjectId;
                    return ListTile(
                      title: Text(
                        dub.languageName,
                        style: TextStyle(
                          color: isCurrent ? AppTheme.accentColor : Colors.white,
                          fontWeight: isCurrent ? FontWeight.bold : FontWeight.normal,
                        ),
                      ),
                      trailing: isCurrent ? const Icon(Icons.check, color: AppTheme.accentColor) : null,
                      onTap: () {
                        Navigator.pop(context);
                        if (!isCurrent) {
                          _switchLanguage(dub);
                        }
                      },
                    );
                  },
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  void _switchLanguage(DubInfo dub) {
    Navigator.pushReplacement(
      context,
      MaterialPageRoute(
        builder: (context) => WatchScreen(
          subject: Subject(
            subjectId: dub.subjectId,
            title: widget.subject.title,
            coverUrl: widget.subject.coverUrl,
            rating: widget.subject.rating,
            releaseYear: widget.subject.releaseYear,
            subjectType: widget.subject.subjectType,
            genres: widget.subject.genres,
            country: widget.subject.country,
            duration: widget.subject.duration,
            description: widget.subject.description,
            seasonCount: widget.subject.seasonCount,
            detailPath: dub.detailPath,
            dubs: widget.subject.dubs,
          ),
          season: _currentSeason,
          episode: _currentEpisode,
        ),
      ),
    );
  }

  Widget _buildVideoWidget(double playerAspectRatio) {
    if (_controller == null || !_controller!.value.isInitialized) {
      return const Center(child: CircularProgressIndicator(color: AppTheme.accentColor));
    }

    final size = _controller!.value.size;
    final width = size.width;
    final height = size.height;

    switch (_scaleMode) {
      case VideoScaleMode.fit:
        return AspectRatio(
          aspectRatio: playerAspectRatio,
          child: VideoPlayer(_controller!),
        );
      case VideoScaleMode.zoom:
        return ClipRect(
          child: SizedBox.expand(
            child: FittedBox(
              fit: BoxFit.cover,
              child: SizedBox(
                width: width,
                height: height,
                child: VideoPlayer(_controller!),
              ),
            ),
          ),
        );
      case VideoScaleMode.stretch:
        return SizedBox.expand(
          child: VideoPlayer(_controller!),
        );
    }
  }

  // Swipe Gestures Handlers
  void _handleVerticalDragUpdate(DragUpdateDetails details, double screenWidth, double screenHeight) {
    final dragDelta = details.primaryDelta! / screenHeight;
    final isLeft = details.globalPosition.dx < screenWidth / 2;

    if (isLeft) {
      // Adjust brightness (Left half swipe)
      setState(() {
        _brightnessValue = (_brightnessValue - dragDelta).clamp(0.0, 1.0);
        _showBrightnessIndicator = true;
      });
      ScreenBrightness().setScreenBrightness(_brightnessValue);
    } else {
      // Adjust volume (Right half swipe with 200% boost capability)
      setState(() {
        _volumeValue = (_volumeValue - dragDelta * 2.0).clamp(0.0, 2.0);
        _showVolumeIndicator = true;
      });
      
      // Set hardware system volume for the first 100% (0.0 to 1.0)
      if (_volumeValue <= 1.0) {
        FlutterVolumeController.setVolume(_volumeValue);
      } else {
        FlutterVolumeController.setVolume(1.0); // cap system volume at 100%
      }
    }
  }

  void _handleVerticalDragEnd(DragEndDetails details) {
    setState(() {
      _showVolumeIndicator = false;
      _showBrightnessIndicator = false;
    });
  }

  void _handleHorizontalDragStart(DragStartDetails details) {
    if (_controller == null || !_controller!.value.isInitialized) return;
    if (_isLocked) return;
    setState(() {
      _showSeekIndicator = true;
      _dragSeekStart = _controller!.value.position.inSeconds.toDouble();
      _dragSeekTarget = _dragSeekStart;
    });
  }

  void _handleHorizontalDragUpdate(DragUpdateDetails details, double screenWidth) {
    if (_controller == null || !_controller!.value.isInitialized) return;
    if (_isLocked) return;
    final duration = _controller!.value.duration.inSeconds.toDouble();
    final dragRatio = details.primaryDelta! / screenWidth;
    
    // Seek speed: swipe screen width seeks full movie duration
    final seekDelta = dragRatio * duration;
    
    setState(() {
      _dragSeekTarget = (_dragSeekTarget + seekDelta).clamp(0.0, duration);
    });
  }

  void _handleHorizontalDragEnd(DragEndDetails details) {
    if (_controller == null || !_controller!.value.isInitialized) return;
    if (_isLocked) return;
    _controller!.seekTo(Duration(seconds: _dragSeekTarget.round()));
    setState(() {
      _showSeekIndicator = false;
    });
  }

  void _doubleTapSeek(bool isRight) {
    if (_controller == null || !_controller!.value.isInitialized) return;
    if (_showControls || _isLocked) return;
    final current = _controller!.value.position;
    final duration = _controller!.value.duration;
    
    Duration target;
    if (isRight) {
      target = current + const Duration(seconds: 10);
      if (target > duration) target = duration;
      _showToastMessage('Fast Forward +10s');
    } else {
      target = current - const Duration(seconds: 10);
      if (target < Duration.zero) target = Duration.zero;
      _showToastMessage('Rewind -10s');
    }
    
    _controller!.seekTo(target);
  }

  // ignore: unused_element
  Future<void> _enterPiP() async {
    if (!_isPipSupported) return;
    try {
      await _floating.enable(ImmediatePiP());
    } catch (e) {
      print('PiP failed: $e');
    }
  }

  Future<void> _switchDub(DubInfo dub) async {
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    final newSubject = await ApiService.fetchDetail(dub.subjectId, dub.detailPath);
    if (newSubject == null) {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Failed to load selected audio track.')),
        );
      }
      return;
    }

    if (!mounted) return;

    // Save current position of the active player to continue watching progress
    final curPos = _controller?.value.position ?? Duration.zero;
    final positionSec = curPos.inSeconds;
    if (positionSec > 5) {
      await Provider.of<ContinueWatchingProvider>(context, listen: false).saveProgress(
        widget.subject,
        season: _currentSeason ?? 0,
        episode: _currentEpisode ?? 0,
        position: positionSec.toDouble(),
        duration: _controller?.value.duration.inSeconds.toDouble() ?? 0,
      );
    }

    Navigator.pushReplacement(
      context,
      MaterialPageRoute(
        builder: (context) => WatchScreen(
          subject: newSubject,
          season: _currentSeason,
          episode: _currentEpisode,
        ),
      ),
    );
  }

  void _showSettingsModal() {
    showModalBottomSheet(
      context: context,
      backgroundColor: AppTheme.surfaceColor,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.only(topLeft: Radius.circular(20), topRight: Radius.circular(20))),
      builder: (context) {
        return StatefulBuilder(
          builder: (context, setModalState) {
            return DefaultTabController(
              length: 3,
              child: Scaffold(
                backgroundColor: Colors.transparent,
                appBar: const PreferredSize(
                  preferredSize: Size.fromHeight(50),
                  child: TabBar(
                    indicatorColor: AppTheme.accentColor,
                    tabs: [
                      Tab(text: 'Quality'),
                      Tab(text: 'Audio'),
                      Tab(text: 'Subtitles'),
                    ],
                  ),
                ),
                body: TabBarView(
                  children: [
                    // Quality panel
                    _resources.isEmpty
                        ? const Center(child: Text('Auto quality active (Local play)', style: TextStyle(color: AppTheme.textMutedColor)))
                        : ListView.builder(
                            itemCount: _resources.length,
                            itemBuilder: (context, idx) {
                              final res = _resources[idx];
                              final isSelected = _selectedResource?.resolution == res.resolution;
                              return ListTile(
                                leading: Icon(Icons.check, color: isSelected ? AppTheme.accentColor : Colors.transparent),
                                title: Text('${res.resolution}p', style: TextStyle(color: isSelected ? AppTheme.accentColor : Colors.white)),
                                onTap: () {
                                  Navigator.pop(context);
                                  if (!isSelected) {
                                    // Save current position
                                    final curPos = _controller!.value.position;
                                    setState(() {
                                      _selectedResource = res;
                                      _isLoading = true;
                                    });
                                    _initializePlayer(CachingProxyServer().getProxyUrl(res.resourceLink));
                                    _controller!.addListener(() {
                                      if (_controller!.value.isInitialized && _isLoading) {
                                        _controller!.seekTo(curPos);
                                      }
                                    });
                                  }
                                },
                              );
                            },
                          ),
                    // Audio panel
                    widget.subject.dubs.isEmpty
                        ? const Center(child: Text('Default Audio (No other tracks)', style: TextStyle(color: AppTheme.textMutedColor)))
                        : ListView.builder(
                            itemCount: widget.subject.dubs.length,
                            itemBuilder: (context, idx) {
                              final dub = widget.subject.dubs[idx];
                              final isSelected = dub.subjectId == widget.subject.subjectId;
                              return ListTile(
                                leading: Icon(Icons.check, color: isSelected ? AppTheme.accentColor : Colors.transparent),
                                title: Text(dub.languageName, style: TextStyle(color: isSelected ? AppTheme.accentColor : Colors.white)),
                                onTap: () {
                                  Navigator.pop(context);
                                  if (!isSelected) {
                                    _switchDub(dub);
                                  }
                                },
                              );
                            },
                          ),
                    // Subtitles panel
                    _captions.isEmpty
                        ? const Center(child: Text('No subtitle tracks available.', style: TextStyle(color: AppTheme.textMutedColor)))
                        : ListView(
                            children: [
                              ListTile(
                                leading: Icon(Icons.check, color: _selectedCaption == null ? AppTheme.accentColor : Colors.transparent),
                                title: Text('Off', style: TextStyle(color: _selectedCaption == null ? AppTheme.accentColor : Colors.white)),
                                onTap: () {
                                  Navigator.pop(context);
                                  setState(() {
                                    _selectedCaption = null;
                                    _subtitleEntries = [];
                                    _currentSubtitleText = '';
                                  });
                                },
                              ),
                              ..._captions.map((cap) {
                                final isSelected = _selectedCaption?.src == cap.src;
                                return ListTile(
                                  leading: Icon(Icons.check, color: isSelected ? AppTheme.accentColor : Colors.transparent),
                                  title: Text(cap.label, style: TextStyle(color: isSelected ? AppTheme.accentColor : Colors.white)),
                                  onTap: () {
                                    Navigator.pop(context);
                                    setState(() {
                                      _selectedCaption = cap;
                                      _isLoading = true;
                                    });
                                    _loadSubtitles(cap.src).then((_) {
                                      if (!mounted) return;
                                      setState(() {
                                        _isLoading = false;
                                      });
                                    });
                                  },
                                );
                              }),
                            ],
                          ),
                  ],
                ),
              ),
            );
          },
        );
      },
    );
  }

  Widget _buildPlayerBody(Size size) {
    final playerAspectRatio = _controller != null && _controller!.value.isInitialized
        ? _controller!.value.aspectRatio
        : 16 / 9;

    final Widget playerContent = GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () {
          if (_isLocked) {
            setState(() {
              _showLockIcon = !_showLockIcon;
            });
            _lockIconTimer?.cancel();
            if (_showLockIcon) {
              _lockIconTimer = Timer(const Duration(seconds: 3), () {
                if (mounted) {
                  setState(() {
                    _showLockIcon = false;
                  });
                }
              });
            }
          } else {
            _toggleControls();
          }
        },
        onVerticalDragUpdate: (d) {
          if (_isLocked) return;
          _handleVerticalDragUpdate(d, size.width, size.height);
        },
        onVerticalDragEnd: (d) {
          if (_isLocked) return;
          _handleVerticalDragEnd(d);
        },
        onHorizontalDragStart: (d) {
          if (_isLocked) return;
          _handleHorizontalDragStart(d);
        },
        onHorizontalDragUpdate: (d) {
          if (_isLocked) return;
          _handleHorizontalDragUpdate(d, size.width);
        },
        onHorizontalDragEnd: (d) {
          if (_isLocked) return;
          _handleHorizontalDragEnd(d);
        },
        child: Stack(
          children: [
            // 1. Video Player View
            Container(
              color: Colors.black,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  Center(
                    child: _buildVideoWidget(playerAspectRatio),
                  ),

                  // 2. Custom Subtitles overlay
                  if (_currentSubtitleText.isNotEmpty)
                    Positioned(
                      bottom: _showControls ? 70 : 25,
                      left: 30,
                      right: 30,
                      child: Center(
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                          decoration: BoxDecoration(
                            color: Colors.black.withOpacity(0.6),
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: Text(
                            _currentSubtitleText,
                            textAlign: TextAlign.center,
                            style: const TextStyle(
                              fontSize: 16,
                              color: Colors.white,
                              fontWeight: FontWeight.w600,
                              shadows: [Shadow(color: Colors.black, offset: Offset(1, 1), blurRadius: 4)],
                            ),
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),

            // 3. Double-tap invisible hitboxes for seeks (only when not locked)
            if (!_isLocked) ...[
              Positioned(
                left: 0,
                top: 50,
                bottom: 50,
                width: size.width * 0.35,
                child: GestureDetector(
                  onDoubleTap: () => _doubleTapSeek(false),
                ),
              ),
              Positioned(
                right: 0,
                top: 50,
                bottom: 50,
                width: size.width * 0.35,
                child: GestureDetector(
                  onDoubleTap: () => _doubleTapSeek(true),
                ),
              ),
            ],

            // 4. Gesture HUD Indicators (Volume / Brightness)
            if (_showVolumeIndicator)
              _buildHUDIndicator(
                Icons.volume_up,
                'Volume: ${(_volumeValue * 100).round()}%',
                _volumeValue / 2.0,
                Colors.blueAccent,
              ),
            if (_showBrightnessIndicator)
              _buildHUDIndicator(
                Icons.brightness_6,
                'Brightness: ${(_brightnessValue * 100).round()}%',
                _brightnessValue,
                Colors.orangeAccent,
              ),
            if (_showSeekIndicator)
              _buildSeekIndicator(),

            // 5. Loading Spinner Overlay
            if (_isLoading || _isBuffering)
              Container(
                color: Colors.black54,
                child: Center(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      const CircularProgressIndicator(color: AppTheme.accentColor),
                      if (_isRetrying) ...[
                        const SizedBox(height: 16),
                        Text(
                          'Connection timeout. Retrying ($_retryCount/$_maxRetries)...',
                          style: const TextStyle(
                            color: Colors.white,
                            fontSize: 14,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
              ),

            // 6. Next Episode Autoplay Overlay (show only when controls are hidden to avoid overlap)
            if (_showNextCountdown && !_showControls)
              _buildNextEpisodeOverlay(),

            // 7. Video Player Controls Overlay
            if (_showControls && !_isLoading && !_isLocked)
              _buildPlayerControlsOverlay(size),

            // 6b. Next Episode overlay above the bottom controls bar
            if (_showNextCountdown && _showControls)
              _buildNextEpisodeOverlay(),

            // 8. Floating Lock Icon (when locked)
            if (_isLocked && _showLockIcon)
              Positioned(
                left: 20,
                top: 0,
                bottom: 0,
                child: Center(
                  child: FloatingActionButton(
                    heroTag: 'unlock_btn',
                    backgroundColor: Colors.black54,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(30),
                      side: const BorderSide(color: AppTheme.accentColor, width: 1.5),
                    ),
                    child: const Icon(Icons.lock, color: AppTheme.accentColor),
                    onPressed: () {
                      setState(() {
                        _isLocked = false;
                        _showLockIcon = false;
                        _showControls = true; // Show controls immediately when unlocking
                      });
                      _showToastMessage('Player Unlocked');
                      _startControlsAutoHide();
                    },
                  ),
                ),
              ),

            // 9. Error Overlay
            if (_errorMessage != null)
              Positioned.fill(
                child: Container(
                  color: const Color(0xE6000000),
                  child: Center(
                    child: SingleChildScrollView(
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          const Icon(
                            Icons.error_outline,
                            color: AppTheme.accentColor,
                            size: 48,
                          ),
                          const SizedBox(height: 12),
                          const Text(
                            'Playback Error',
                            style: TextStyle(
                              color: Colors.white,
                              fontSize: 16,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                          const SizedBox(height: 6),
                          Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 24),
                            child: Text(
                              _errorMessage!,
                              textAlign: TextAlign.center,
                              style: TextStyle(
                                color: Colors.white.withOpacity(0.7),
                                fontSize: 12,
                              ),
                            ),
                          ),
                          const SizedBox(height: 20),
                          Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              ElevatedButton.icon(
                                style: ElevatedButton.styleFrom(
                                  backgroundColor: AppTheme.accentColor,
                                  foregroundColor: Colors.white,
                                  shape: RoundedRectangleBorder(
                                    borderRadius: BorderRadius.circular(8),
                                  ),
                                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                                ),
                                icon: const Icon(Icons.refresh, size: 18),
                                label: const Text('Retry'),
                                onPressed: () {
                                  if (_currentUrl != null) {
                                    _initializePlayer(_currentUrl!, isLocal: _currentIsLocal);
                                  } else {
                                    _loadStreamInfo();
                                  }
                                },
                              ),
                              const SizedBox(width: 12),
                              OutlinedButton.icon(
                                style: OutlinedButton.styleFrom(
                                  foregroundColor: Colors.white,
                                  side: const BorderSide(color: Colors.white30),
                                  shape: RoundedRectangleBorder(
                                    borderRadius: BorderRadius.circular(8),
                                  ),
                                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                                ),
                                icon: const Icon(Icons.arrow_back, size: 18),
                                label: const Text('Go Back'),
                                onPressed: () {
                                  Navigator.pop(context);
                                },
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
          ],
        ),
      );

    if (_isFullscreen || MediaQuery.of(context).orientation == Orientation.landscape) {
      return SizedBox.expand(child: playerContent);
    } else {
      return AspectRatio(
        aspectRatio: 16 / 9,
        child: playerContent,
      );
    }
  }

  Widget _buildPortraitMetadata() {
    return Row(
      children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
          decoration: BoxDecoration(
            color: Colors.white12,
            borderRadius: BorderRadius.circular(4),
          ),
          child: Row(
            children: [
              const Icon(Icons.star, color: Colors.amber, size: 12),
              const SizedBox(width: 4),
              Text(
                widget.subject.rating,
                style: const TextStyle(fontSize: 11, fontWeight: FontWeight.bold, color: Colors.white),
              ),
            ],
          ),
        ),
        const SizedBox(width: 12),
        Text(widget.subject.releaseYear, style: const TextStyle(fontSize: 12, color: Colors.white)),
        const SizedBox(width: 12),
        Text(widget.subject.country, style: const TextStyle(fontSize: 12, color: AppTheme.textMutedColor)),
        const SizedBox(width: 12),
        Text(widget.subject.duration, style: const TextStyle(fontSize: 12, color: AppTheme.textMutedColor)),
      ],
    );
  }

  Widget _buildActionButtonsRow() {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: [
          // 1. Add to List
          _buildActionButton(
            icon: _isInWatchlist ? Icons.check : Icons.add,
            label: _isInWatchlist ? 'In list' : 'Add to list',
            onPressed: () {
              setState(() {
                _isInWatchlist = !_isInWatchlist;
              });
              _showToastMessage(_isInWatchlist ? 'Added to watchlist' : 'Removed from watchlist');
            },
          ),
          const SizedBox(width: 8),
          
          // 2. Share
          _buildActionButton(
            icon: Icons.share,
            label: 'Share',
            onPressed: () {
              _showToastMessage('Sharing link: ${widget.subject.title}');
            },
          ),
          const SizedBox(width: 8),
          
          // 3. Download (opens bottom sheet)
          _buildActionButton(
            icon: Icons.download,
            label: 'Download',
            onPressed: _showDownloadBottomSheet,
          ),
          const SizedBox(width: 8),
          
          // 4. View downloads
          _buildActionButton(
            icon: Icons.download_done,
            label: 'View downloads',
            onPressed: () {
              Navigator.push(
                context,
                MaterialPageRoute(builder: (context) => const DownloadsScreen()),
              );
            },
          ),
          const SizedBox(width: 8),
          
          // 5. Details
          _buildActionButton(
            icon: Icons.info_outline,
            label: 'Details',
            onPressed: () {
              Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (context) => DetailsScreen(subjectId: widget.subject.subjectId, detailPath: widget.subject.detailPath),
                ),
              );
            },
          ),
        ],
      ),
    );
  }

  Widget _buildActionButton({required IconData icon, required String label, required VoidCallback onPressed}) {
    return ElevatedButton.icon(
      style: ElevatedButton.styleFrom(
        backgroundColor: AppTheme.cardColor,
        foregroundColor: Colors.white,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(20),
          side: const BorderSide(color: AppTheme.borderSubtle),
        ),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      ),
      icon: Icon(icon, color: Colors.white, size: 14),
      label: Text(label, style: const TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.bold)),
      onPressed: onPressed,
    );
  }

  void _showDownloadBottomSheet() {
    final downloadsProvider = Provider.of<DownloadsProvider>(context, listen: false);
    final isTv = widget.subject.seasonCount > 0 || widget.subject.subjectType == 2;
    
    // Find unique resolutions from current _resources or fallback to common ones
    final List<int> availableResolutions = _resources.map((r) => r.resolution).toSet().toList();
    if (availableResolutions.isEmpty) {
      availableResolutions.addAll([360, 720, 1080]);
    }
    availableResolutions.sort(); // ascending

    int selectedResolution = _selectedResource?.resolution ?? 720;
    if (!availableResolutions.contains(selectedResolution)) {
      selectedResolution = availableResolutions.last;
    }

    // List of episodes/items to display
    final List<int> episodesList = [];
    if (isTv && _seasons.isNotEmpty) {
      final currentSeasonObj = _seasons.firstWhere(
        (s) => s.seasonNumber == _currentSeason,
        orElse: () => _seasons[0],
      );
      episodesList.addAll(currentSeasonObj.episodes);
    } else {
      episodesList.add(0); // Movie representation
    }

    // Keep track of check state in the stateful builder
    List<int> checkedEpisodes = [];

    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      isScrollControlled: true,
      barrierColor: Colors.black.withOpacity(0.5),
      builder: (context) {
        return StatefulBuilder(
          builder: (context, setModalState) {
            // Check completed status for disabled state
            bool isAllChecked() {
              final unDownloaded = episodesList.where((ep) {
                return !downloadsProvider.isDownloaded(widget.subject.subjectId, season: isTv ? (_currentSeason ?? 1) : 0, episode: ep);
              }).toList();
              if (unDownloaded.isEmpty) return false;
              return unDownloaded.every((ep) => checkedEpisodes.contains(ep));
            }

            void toggleSelectAll(bool? checked) {
              setModalState(() {
                if (checked == true) {
                  checkedEpisodes = episodesList.where((ep) {
                    return !downloadsProvider.isDownloaded(widget.subject.subjectId, season: isTv ? (_currentSeason ?? 1) : 0, episode: ep);
                  }).toList();
                } else {
                  checkedEpisodes.clear();
                }
              });
            }

            return ClipRRect(
              borderRadius: const BorderRadius.only(topLeft: Radius.circular(24), topRight: Radius.circular(24)),
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 12, sigmaY: 12),
                child: Container(
                  color: AppTheme.surfaceColor.withOpacity(0.85),
                  padding: EdgeInsets.only(
                    bottom: MediaQuery.of(context).viewInsets.bottom,
                  ),
                  height: MediaQuery.of(context).size.height * 0.65,
                  child: Column(
                    children: [
                      // Header title
                      Padding(
                        padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
                        child: Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            const Text(
                              'Download',
                              style: TextStyle(fontFamily: 'Outfit', fontSize: 20, fontWeight: FontWeight.bold, color: Colors.white),
                            ),
                            IconButton(
                              icon: const Icon(Icons.close, color: Colors.white),
                              onPressed: () => Navigator.pop(context),
                            ),
                          ],
                        ),
                      ),
                      const Divider(color: AppTheme.borderSubtle, height: 1),

                      // Resources info
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                        child: Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            Expanded(
                              child: Text(
                                'Resources Uploaded by Raeesah Mussá etc.',
                                style: TextStyle(color: Colors.white.withOpacity(0.7), fontSize: 12),
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                            const Icon(Icons.info_outline, color: AppTheme.textMutedColor, size: 14),
                          ],
                        ),
                      ),

                      // Audio track selector
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 16),
                        child: Align(
                          alignment: Alignment.centerLeft,
                          child: Container(
                            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                            decoration: BoxDecoration(
                              color: AppTheme.cardColor,
                              borderRadius: BorderRadius.circular(8),
                              border: Border.all(color: AppTheme.borderSubtle),
                            ),
                            child: DropdownButtonHideUnderline(
                              child: DropdownButton<String>(
                                value: 'Original Audio',
                                dropdownColor: AppTheme.surfaceColor,
                                icon: const Icon(Icons.keyboard_arrow_down, color: AppTheme.accentColor, size: 16),
                                style: const TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.bold),
                                items: const [
                                  DropdownMenuItem(
                                    value: 'Original Audio',
                                    child: Text('Original Audio'),
                                  ),
                                  DropdownMenuItem(
                                    value: 'Hindi Dub',
                                    child: Text('Hindi Dub'),
                                  ),
                                  DropdownMenuItem(
                                    value: 'English Dub',
                                    child: Text('English Dub'),
                                  ),
                                ],
                                onChanged: (_) {},
                              ),
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(height: 12),

                      // Resolutions chips/segment
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 16),
                        child: Row(
                          children: availableResolutions.map((res) {
                            final isSelected = selectedResolution == res;
                            return Padding(
                              padding: const EdgeInsets.only(right: 8),
                              child: ChoiceChip(
                                label: Text('${res}P'),
                                selected: isSelected,
                                selectedColor: AppTheme.accentColor,
                                labelStyle: TextStyle(
                                  color: isSelected ? Colors.white : AppTheme.textMutedColor,
                                  fontWeight: FontWeight.bold,
                                  fontSize: 12,
                                ),
                                backgroundColor: AppTheme.cardColor,
                                onSelected: (_) {
                                  setModalState(() {
                                    selectedResolution = res;
                                  });
                                },
                              ),
                            );
                          }).toList(),
                        ),
                      ),
                      const SizedBox(height: 12),

                      // Episodes list checklist
                      Expanded(
                        child: ListView.builder(
                          itemCount: episodesList.length,
                          itemBuilder: (context, idx) {
                            final ep = episodesList[idx];
                            final seasonNum = isTv ? (_currentSeason ?? 1) : 0;
                            
                            final isCompleted = downloadsProvider.isDownloaded(
                              widget.subject.subjectId,
                              season: seasonNum,
                              episode: ep,
                            );
                            final existing = downloadsProvider.getDownload(
                              widget.subject.subjectId,
                              season: seasonNum,
                              episode: ep,
                            );
                            final isDownloading = existing != null && (existing.status == 'downloading' || existing.status == 'pending');

                            String titleText;
                            if (isTv) {
                              titleText = 'E${ep.toString().padLeft(2, '0')} · Episode $ep';
                            } else {
                              titleText = 'Full Movie';
                            }

                            final sizeStr = selectedResolution == 1080 
                                ? '557.3 MB' 
                                : (selectedResolution == 720 ? '380.0 MB' : '180.5 MB');
                            final durationStr = isTv ? '23:34' : widget.subject.duration;

                            return ListTile(
                              leading: isCompleted
                                  ? const Icon(Icons.check_circle, color: AppTheme.accentColor)
                                  : isDownloading
                                      ? const SizedBox(
                                          width: 20,
                                          height: 20,
                                          child: CircularProgressIndicator(strokeWidth: 2, color: AppTheme.accentColor),
                                        )
                                      : Checkbox(
                                          value: checkedEpisodes.contains(ep),
                                          activeColor: AppTheme.accentColor,
                                          checkColor: Colors.white,
                                          onChanged: (val) {
                                            setModalState(() {
                                              if (val == true) {
                                                checkedEpisodes.add(ep);
                                              } else {
                                                checkedEpisodes.remove(ep);
                                              }
                                            });
                                          },
                                        ),
                              title: Text(
                                titleText,
                                style: TextStyle(
                                  color: isCompleted ? AppTheme.textMutedColor : Colors.white,
                                  fontWeight: FontWeight.bold,
                                  fontSize: 14,
                                ),
                              ),
                              subtitle: Text(
                                isCompleted 
                                    ? 'Downloaded' 
                                    : (existing != null && existing.status == 'pending')
                                        ? 'Queued...'
                                        : isDownloading 
                                            ? 'Downloading...' 
                                            : '$sizeStr | $durationStr',
                                style: const TextStyle(color: AppTheme.textMutedColor, fontSize: 12),
                              ),
                              onTap: (isCompleted || isDownloading) 
                                  ? null 
                                  : () {
                                      setModalState(() {
                                        if (checkedEpisodes.contains(ep)) {
                                          checkedEpisodes.remove(ep);
                                        } else {
                                          checkedEpisodes.add(ep);
                                        }
                                      });
                                    },
                            );
                          },
                        ),
                      ),

                      // Bottom actions row
                      const Divider(color: AppTheme.borderSubtle, height: 1),
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                        color: AppTheme.cardColor.withOpacity(0.5),
                        child: Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            Row(
                              children: [
                                Checkbox(
                                  value: isAllChecked(),
                                  activeColor: AppTheme.accentColor,
                                  checkColor: Colors.white,
                                  onChanged: toggleSelectAll,
                                ),
                                const Text(
                                  'Select All',
                                  style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 13),
                                ),
                              ],
                            ),
                            ElevatedButton(
                              style: ElevatedButton.styleFrom(
                                padding: EdgeInsets.zero,
                                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
                              ),
                              onPressed: checkedEpisodes.isEmpty 
                                  ? null 
                                  : () async {
                                      Navigator.pop(context);
                                      _showToastMessage('Added ${checkedEpisodes.length} items to queue.');
                                      
                                      for (final ep in checkedEpisodes) {
                                        _startBackgroundDownload(ep, selectedResolution);
                                      }
                                    },
                              child: Container(
                                decoration: BoxDecoration(
                                  gradient: const LinearGradient(
                                    colors: [Color(0xFF00FFB2), Color(0xFF00FF7F)],
                                    begin: Alignment.topLeft,
                                    end: Alignment.bottomRight,
                                  ),
                                  borderRadius: BorderRadius.circular(24),
                                ),
                                padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                                alignment: Alignment.center,
                                child: Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    const Icon(Icons.download, color: Colors.black, size: 16),
                                    const SizedBox(width: 8),
                                    Text(
                                      'Download (${checkedEpisodes.length})',
                                      style: const TextStyle(color: Colors.black, fontWeight: FontWeight.bold, fontSize: 13),
                                    ),
                                  ],
                                ),
                              ),
                            )
                          ],
                        ),
                      )
                    ],
                  ),
                ),
              ),
            );
          },
        );
      },
    );
  }

  Future<void> _startBackgroundDownload(int episode, int resolution) async {
    final downloadsProvider = Provider.of<DownloadsProvider>(context, listen: false);
    final isTv = widget.subject.seasonCount > 0 || widget.subject.subjectType == 2;
    final seasonNum = isTv ? (_currentSeason ?? 1) : 0;

    try {
      final resources = await ApiService.fetchPlayResources(
        widget.subject.subjectId,
        detailPath: widget.subject.detailPath,
        se: isTv ? seasonNum : null,
        ep: isTv ? episode : null,
      );

      if (resources.isEmpty) {
        print('No resources found for background download of episode $episode');
        return;
      }

      PlayResource? selectedRes;
      final matchIdx = resources.indexWhere((r) => r.resolution == resolution);
      if (matchIdx != -1) {
        selectedRes = resources[matchIdx];
      } else {
        selectedRes = resources.reduce((a, b) => (a.resolution - resolution).abs() < (b.resolution - resolution).abs() ? a : b);
      }

      await downloadsProvider.startDownload(
        widget.subject,
        selectedRes,
        season: seasonNum,
        episode: episode,
      );
    } catch (e) {
      print('Background download error for episode $episode: $e');
    }
  }

  Widget _buildResourcesList() {
    return SizedBox(
      height: 36,
      child: ListView.builder(
        scrollDirection: Axis.horizontal,
        itemCount: _resources.length,
        itemBuilder: (context, idx) {
          final res = _resources[idx];
          final isSelected = _selectedResource?.resolution == res.resolution;
          return Padding(
            padding: const EdgeInsets.only(right: 6),
            child: InkWell(
              onTap: () {
                if (!isSelected) {
                  setState(() {
                    _selectedResource = res;
                    _isLoading = true;
                  });
                  // Cancel any ongoing proxy pre-buffering and dispose old
                  // controller before starting the new resource stream.
                  // _initializePlayer already handles disposal via initId guard.
                  _initializePlayer(CachingProxyServer().getProxyUrl(res.resourceLink));
                }
              },
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
                decoration: BoxDecoration(
                  color: isSelected ? AppTheme.accentColor : AppTheme.cardColor,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: isSelected ? Colors.transparent : AppTheme.borderSubtle),
                ),
                child: Center(
                  child: Text(
                    '${res.resolution}p',
                    style: TextStyle(
                      fontWeight: FontWeight.bold,
                      fontSize: 12,
                      color: isSelected ? Colors.white : AppTheme.textColor,
                    ),
                  ),
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _buildStreamLinksList() {
    final links = widget.liveLinks;
    if (links == null || links.isEmpty) return const SizedBox.shrink();
    return SizedBox(
      height: 36,
      child: ListView.builder(
        scrollDirection: Axis.horizontal,
        itemCount: links.length,
        itemBuilder: (context, idx) {
          final link = links[idx];
          final isActive = idx == _currentLiveIndex;
          return Padding(
            padding: const EdgeInsets.only(right: 6),
            child: InkWell(
              onTap: isActive
                  ? null
                  : () {
                      setState(() {
                        _currentLiveIndex = idx;
                        _isLoading = true;
                      });
                      _controller?.removeListener(_onControllerUpdate);
                      final old = _controller;
                      _controller = null;
                      old?.dispose();
                      final encodedUrl = Uri.encodeComponent(link.url);
                      final encodedRef = Uri.encodeComponent(link.referer);
                      final encodedOrig = Uri.encodeComponent(link.origin);
                      final encodedUA = Uri.encodeComponent(link.userAgent);
                      final useProxy = link.useBdProxy ? 'true' : 'false';
                      final proxiedUrl =
                          '${Constants.baseUrl}/api/sports/proxy?url=$encodedUrl&referer=$encodedRef&origin=$encodedOrig&userAgent=$encodedUA&use_bd_proxy=$useProxy';
                      _initializePlayer(proxiedUrl);
                    },
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
                decoration: BoxDecoration(
                  color: isActive ? AppTheme.accentColor : AppTheme.cardColor,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(
                      color: isActive ? Colors.transparent : AppTheme.borderSubtle),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (isActive) ...[
                      const Icon(Icons.circle, size: 8, color: Colors.greenAccent),
                      const SizedBox(width: 5),
                    ],
                    Text(
                      link.label.isNotEmpty ? link.label : 'Link ${idx + 1}',
                      style: TextStyle(
                        fontWeight: FontWeight.bold,
                        fontSize: 12,
                        color: isActive ? Colors.white : AppTheme.textColor,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _buildSeasonSelector() {
    return SizedBox(
      height: 34,
      child: ListView.builder(
        scrollDirection: Axis.horizontal,
        itemCount: _seasons.length,
        itemBuilder: (context, idx) {
          final s = _seasons[idx];
          final isSelected = s.seasonNumber == _currentSeason;
          return Padding(
            padding: const EdgeInsets.only(right: 8),
            child: ChoiceChip(
              label: Text('Season ${s.seasonNumber}'),
              selected: isSelected,
              selectedColor: AppTheme.accentColor,
              labelStyle: TextStyle(color: isSelected ? Colors.white : AppTheme.textMutedColor, fontWeight: FontWeight.bold),
              backgroundColor: AppTheme.cardColor,
              onSelected: (_) {
                setState(() {
                  _currentSeason = s.seasonNumber;
                  _currentEpisode = s.episodes.isNotEmpty ? s.episodes[0] : 1;
                });
                _loadStreamInfo();
              },
            ),
          );
        },
      ),
    );
  }

  Widget _buildEpisodeSelector() {
    final seasonObj = _seasons.firstWhere((s) => s.seasonNumber == _currentSeason, orElse: () => _seasons[0]);
    final downloadsProvider = Provider.of<DownloadsProvider>(context);

    return SizedBox(
      height: 38,
      child: ListView.builder(
        scrollDirection: Axis.horizontal,
        itemCount: seasonObj.episodes.length,
        itemBuilder: (context, idx) {
          final ep = seasonObj.episodes[idx];
          final isSelected = ep == _currentEpisode;
          final isDownloaded = downloadsProvider.isDownloaded(
            widget.subject.subjectId,
            season: _currentSeason ?? 1,
            episode: ep,
          );

          return Padding(
            padding: const EdgeInsets.only(right: 8),
            child: InkWell(
              onTap: () {
                setState(() {
                  _currentEpisode = ep;
                });
                _loadStreamInfo();
              },
              borderRadius: BorderRadius.circular(6),
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 14),
                decoration: BoxDecoration(
                  color: isSelected ? AppTheme.accentColor : AppTheme.cardColor,
                  borderRadius: BorderRadius.circular(6),
                  border: Border.all(color: isSelected ? Colors.transparent : AppTheme.borderSubtle),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      'Ep $ep',
                      style: TextStyle(
                        fontWeight: FontWeight.bold,
                        fontSize: 12,
                        color: isSelected ? Colors.white : AppTheme.textColor,
                      ),
                    ),
                    if (isDownloaded) ...[
                      const SizedBox(width: 4),
                      const Icon(Icons.download_done_rounded, color: Colors.white, size: 12),
                    ],
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _buildRecommendationsGrid() {
    return GridView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      itemCount: _recommendations.length,
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 3,
        mainAxisSpacing: 10,
        crossAxisSpacing: 10,
        childAspectRatio: 0.58,
      ),
      itemBuilder: (context, index) {
        final item = _recommendations[index];
        return GestureDetector(
          onTap: () {
            Navigator.pushReplacement(
              context,
              MaterialPageRoute(
                builder: (context) => WatchScreen(
                  subject: item,
                ),
              ),
            );
          },
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(12),
                  child: CachedNetworkImage(
                    imageUrl: item.coverUrl,
                    width: double.infinity,
                    height: double.infinity,
                    fit: BoxFit.cover,
                    memCacheWidth: 200,
                    errorWidget: (context, url, error) => Container(color: AppTheme.cardColor),
                  ),
                ),
              ),
              const SizedBox(height: 6),
              Text(
                item.title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: Colors.white),
              ),
            ],
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    if (_isInPipMode) {
      return Scaffold(
        backgroundColor: Colors.black,
        body: _controller != null && _controller!.value.isInitialized
            ? VideoPlayer(_controller!)
            : const Center(child: CircularProgressIndicator(color: AppTheme.accentColor)),
      );
    }

    final size = MediaQuery.of(context).size;
    final isTv = widget.subject.seasonCount > 0 || widget.subject.subjectType == 2;
    final isLandscape = MediaQuery.of(context).orientation == Orientation.landscape;
    final showFullscreen = _isFullscreen || isLandscape;

    Widget body;
    if (showFullscreen) {
      body = Scaffold(
        backgroundColor: Colors.black,
        body: _buildPlayerBody(size),
      );
    } else {
      body = Scaffold(
        backgroundColor: AppTheme.backgroundColor,
        body: SafeArea(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const SearchHeader(showBackButton: true),
              _buildPlayerBody(size),
              Expanded(
                child: SingleChildScrollView(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          widget.subject.title,
                          style: const TextStyle(fontSize: 17, fontWeight: FontWeight.bold, color: Colors.white),
                        ),
                        const SizedBox(height: 4),
                        _buildPortraitMetadata(),
                        const SizedBox(height: 10),
                        _buildActionButtonsRow(),
                        const SizedBox(height: 12),
                        if (_resources.isNotEmpty) ...[
                          const Text(
                            'Resources',
                            style: TextStyle(fontSize: 13, fontWeight: FontWeight.bold, color: Colors.white),
                          ),
                          const SizedBox(height: 6),
                          _buildResourcesList(),
                          const SizedBox(height: 12),
                        ],
                        if (widget.isLive && widget.liveLinks != null && widget.liveLinks!.isNotEmpty) ...[
                          const Text(
                            'Stream Links',
                            style: TextStyle(fontSize: 13, fontWeight: FontWeight.bold, color: Colors.white),
                          ),
                          const SizedBox(height: 6),
                          _buildStreamLinksList(),
                          const SizedBox(height: 12),
                        ],
                        if (isTv && _seasons.isNotEmpty) ...[
                          const Text(
                            'Episodes',
                            style: TextStyle(fontSize: 13, fontWeight: FontWeight.bold, color: Colors.white),
                          ),
                          const SizedBox(height: 8),
                          _buildSeasonSelector(),
                          const SizedBox(height: 8),
                          _buildEpisodeSelector(),
                          const SizedBox(height: 14),
                        ],
                        const Text(
                          'For You',
                          style: TextStyle(fontSize: 13, fontWeight: FontWeight.bold, color: Colors.white),
                        ),
                        const SizedBox(height: 8),
                        if (_recommendations.isNotEmpty)
                          _buildRecommendationsGrid()
                        else
                          const Center(child: Text('No recommendations available.', style: TextStyle(color: AppTheme.textMutedColor))),
                        const SizedBox(height: 16),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      );
    }

    return PopScope(
      canPop: !_isFullscreen,
      onPopInvokedWithResult: (didPop, result) {
        if (didPop) return;
        if (_isFullscreen) {
          _toggleFullscreen();
        }
      },
      child: body,
    );
  }

  Widget _buildHUDIndicator(IconData icon, String label, double progress, Color progressColor) {
    return Center(
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: Colors.black87,
          borderRadius: BorderRadius.circular(16),
        ),
        width: 140,
        height: 120,
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, color: progressColor, size: 36),
            const SizedBox(height: 8),
            Text(label, style: const TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            LinearProgressIndicator(
              value: progress,
              backgroundColor: Colors.white24,
              valueColor: AlwaysStoppedAnimation<Color>(progressColor),
            )
          ],
        ),
      ),
    );
  }

  Widget _buildSeekIndicator() {
    final start = Duration(seconds: _dragSeekStart.round()).toString().split('.').first;
    final target = Duration(seconds: _dragSeekTarget.round()).toString().split('.').first;
    final diff = (_dragSeekTarget - _dragSeekStart).round();
    final sign = diff >= 0 ? '+' : '';

    return Center(
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
        decoration: BoxDecoration(
          color: Colors.black87,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.sync_alt, color: AppTheme.accentColor, size: 32),
            const SizedBox(height: 6),
            Text(
              '$target / $start ($sign${diff}s)',
              style: const TextStyle(color: Colors.white, fontSize: 14, fontWeight: FontWeight.bold),
            )
          ],
        ),
      ),
    );
  }

  Widget _buildNextEpisodeOverlay() {
    return Positioned(
      bottom: 60,
      right: 16,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        decoration: BoxDecoration(
          color: AppTheme.cardColor,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: AppTheme.borderSubtle),
          boxShadow: const [BoxShadow(color: Colors.black54, blurRadius: 10)],
        ),
        width: 190,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              'Next · Ep ${(_currentEpisode ?? 0) + 1}',
              style: const TextStyle(color: AppTheme.textMutedColor, fontSize: 11),
            ),
            const SizedBox(height: 6),
            Row(
              children: [
                Expanded(
                  child: ElevatedButton(
                    style: ElevatedButton.styleFrom(
                      backgroundColor: AppTheme.accentColor,
                      padding: const EdgeInsets.symmetric(vertical: 7),
                      minimumSize: Size.zero,
                    ),
                    onPressed: () {
                      _countdownTimer?.cancel();
                      _playNextEpisode();
                    },
                    child: Text('Play ($_countdownSeconds)', style: const TextStyle(color: Colors.white, fontSize: 12)),
                  ),
                ),
                const SizedBox(width: 4),
                IconButton(
                  icon: const Icon(Icons.close, color: Colors.white, size: 18),
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints(),
                  onPressed: () {
                    setState(() { _showNextCountdown = false; });
                    _countdownTimer?.cancel();
                  },
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildPlayerControlsOverlay(Size size) {
    if (_controller == null) return const SizedBox.shrink();

    final isTv = widget.subject.seasonCount > 0 || widget.subject.subjectType == 2;
    final title = isTv ? '${widget.subject.title} - S${_currentSeason}E${_currentEpisode.toString().padLeft(2, '0')}' : widget.subject.title;

    final isLandscape = MediaQuery.of(context).orientation == Orientation.landscape;

    return ValueListenableBuilder<VideoPlayerValue>(
      valueListenable: _controller!,
      builder: (context, value, child) {
        final position = value.position;
        final duration = value.duration;
        final isPlaying = value.isPlaying;

        final currentDurationForText = _isDragging 
            ? Duration(seconds: _dragValue.round()) 
            : position;
        final posStr = currentDurationForText.toString().split('.').first;
        final durStr = duration.toString().split('.').first;

        if (isLandscape) {
          // Landscape: Pixel-perfect overlay from screenshots
          return Container(
            decoration: const BoxDecoration(
              gradient: LinearGradient(
                colors: [Colors.black87, Colors.transparent, Colors.black87],
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
              ),
            ),
            child: SafeArea(
              child: Stack(
                children: [
                  // 1. Top Bar
                  Positioned(
                    top: 0,
                    left: 0,
                    right: 0,
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                      child: Row(
                        children: [
                          IconButton(
                            icon: const Icon(Icons.arrow_back_ios_new_rounded, color: Colors.white, size: 20),
                            onPressed: () {
                              if (_isFullscreen) {
                                _toggleFullscreen();
                              } else {
                                Navigator.pop(context);
                              }
                            },
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              title,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14, color: Colors.white),
                            ),
                          ),
                          // Help Column Button
                          GestureDetector(
                            onTap: _showHelpDialog,
                            child: const Padding(
                              padding: EdgeInsets.symmetric(horizontal: 12),
                              child: Column(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Icon(Icons.help_outline_rounded, color: Colors.white, size: 20),
                                  SizedBox(height: 2),
                                  Text('Help', style: TextStyle(fontSize: 10, color: Colors.white70)),
                                ],
                              ),
                            ),
                          ),
                          // Setting Column Button
                          GestureDetector(
                            onTap: _showSettingsModal,
                            child: const Padding(
                              padding: EdgeInsets.symmetric(horizontal: 12),
                              child: Column(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Icon(Icons.settings_outlined, color: Colors.white, size: 20),
                                  SizedBox(height: 2),
                                  Text('Setting', style: TextStyle(fontSize: 10, color: Colors.white70)),
                                ],
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),

                  // 2. Lock Toggle Floating Button (Left Center)
                  Positioned(
                    left: 20,
                    top: 0,
                    bottom: 0,
                    child: Center(
                      child: GestureDetector(
                        onTap: () {
                          setState(() {
                            _isLocked = true;
                            _showControls = false;
                            _showLockIcon = true;
                          });
                          _showToastMessage('Player Locked');
                          _lockIconTimer?.cancel();
                          _lockIconTimer = Timer(const Duration(seconds: 3), () {
                            if (mounted) {
                              setState(() {
                                _showLockIcon = false;
                              });
                            }
                          });
                        },
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                          decoration: BoxDecoration(
                            color: Colors.black54,
                            borderRadius: BorderRadius.circular(20),
                            border: Border.all(color: Colors.white24, width: 1.0),
                          ),
                          child: const Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Icon(Icons.lock_outline, color: Colors.white, size: 16),
                              SizedBox(width: 6),
                              Text('Lock', style: TextStyle(fontSize: 11, color: Colors.white, fontWeight: FontWeight.bold)),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),

                  // 3. Center Rewind, Large Play/Pause, Fast Forward
                  Center(
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        IconButton(
                          icon: const Icon(Icons.replay_10_rounded, color: Colors.white, size: 40),
                          onPressed: () {
                            final newPos = position - const Duration(seconds: 10);
                            _controller!.seekTo(newPos < Duration.zero ? Duration.zero : newPos);
                            _startControlsAutoHide();
                          },
                        ),
                        const SizedBox(width: 44),
                        GestureDetector(
                          onTap: () {
                            if (isPlaying) {
                              _controller!.pause();
                            } else {
                              _controller!.play();
                            }
                            _startControlsAutoHide();
                          },
                          child: Container(
                            width: 60,
                            height: 60,
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              color: Colors.black54,
                              border: Border.all(color: Colors.white, width: 2.0),
                            ),
                            child: Icon(
                              isPlaying ? Icons.pause_rounded : Icons.play_arrow_rounded,
                              color: Colors.white,
                              size: 36,
                            ),
                          ),
                        ),
                        const SizedBox(width: 44),
                        IconButton(
                          icon: const Icon(Icons.forward_10_rounded, color: Colors.white, size: 40),
                          onPressed: () {
                            final newPos = position + const Duration(seconds: 10);
                            _controller!.seekTo(newPos > duration ? duration : newPos);
                            _startControlsAutoHide();
                          },
                        ),
                      ],
                    ),
                  ),

                  // 4. Bottom Controls Row
                  Positioned(
                    bottom: 0,
                    left: 0,
                    right: 0,
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                      child: Row(
                        children: [
                          // Small Play/Pause
                          GestureDetector(
                            onTap: () {
                              if (isPlaying) {
                                _controller!.pause();
                              } else {
                                _controller!.play();
                              }
                              _startControlsAutoHide();
                            },
                            child: Icon(
                              isPlaying ? Icons.pause_rounded : Icons.play_arrow_rounded,
                              color: Colors.white,
                              size: 20,
                            ),
                          ),
                          const SizedBox(width: 10),
                          
                          // Current time
                          Text(posStr, style: const TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.bold)),
                          
                          // Cyan/Green Slider
                          Expanded(
                            child: SliderTheme(
                              data: SliderTheme.of(context).copyWith(
                                trackHeight: 2,
                                thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 5),
                                activeTrackColor: AppTheme.accentColor,
                                inactiveTrackColor: Colors.white24,
                                thumbColor: AppTheme.accentColor,
                              ),
                              child: Slider(
                                min: 0.0,
                                max: duration.inSeconds.toDouble(),
                                value: _isDragging 
                                    ? _dragValue 
                                    : position.inSeconds.toDouble().clamp(0.0, duration.inSeconds.toDouble()),
                                onChangeStart: (val) {
                                  setState(() {
                                    _isDragging = true;
                                    _dragValue = val;
                                  });
                                  _controlsTimer?.cancel();
                                },
                                onChanged: (val) {
                                  setState(() {
                                    _dragValue = val;
                                  });
                                },
                                onChangeEnd: (val) async {
                                  if (_controller != null) {
                                    await _controller!.seekTo(Duration(seconds: val.round()));
                                  }
                                  setState(() {
                                    _isDragging = false;
                                  });
                                  _startControlsAutoHide();
                                },
                              ),
                            ),
                          ),
                          
                          // Total duration
                          Text(durStr, style: const TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.bold)),
                          const SizedBox(width: 16),

                          // Fit Toggle Button
                          GestureDetector(
                            onTap: _toggleScaleMode,
                            child: Text(
                              _scaleMode == VideoScaleMode.fit ? 'Fit' : (_scaleMode == VideoScaleMode.zoom ? 'Zoom' : 'Stretch'),
                              style: const TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.bold),
                            ),
                          ),
                          const SizedBox(width: 16),

                          // Language selector Button
                          GestureDetector(
                            onTap: _showLanguageSelector,
                            child: const Text(
                              'Language',
                              style: TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.bold),
                            ),
                          ),
                          const SizedBox(width: 16),

                          // Speed Selector Button
                          GestureDetector(
                            onTap: _toggleSpeed,
                            child: Text(
                              '${_playbackSpeed}x',
                              style: const TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.bold),
                            ),
                          ),
                          const SizedBox(width: 16),

                          // Quality Display Text
                          Text(
                            _selectedResource != null ? '${_selectedResource!.resolution}P' : '1080P',
                            style: const TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.bold),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          );
        } else {
          // Portrait Controls
          return Container(
            decoration: const BoxDecoration(
              gradient: LinearGradient(
                colors: [Colors.black54, Colors.transparent, Colors.black54],
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
              ),
            ),
            child: SafeArea(
              child: Stack(
                children: [
                  // Lock button on the left edge
                  Positioned(
                    left: 20,
                    top: 0,
                    bottom: 0,
                    child: Center(
                      child: IconButton(
                        icon: const Icon(Icons.lock_open_rounded, color: Colors.white, size: 24),
                        onPressed: () {
                          setState(() {
                            _isLocked = true;
                            _showControls = false;
                            _showLockIcon = true;
                          });
                          _showToastMessage('Player Locked');
                          _lockIconTimer?.cancel();
                          _lockIconTimer = Timer(const Duration(seconds: 3), () {
                            if (mounted) {
                              setState(() {
                                _showLockIcon = false;
                              });
                            }
                          });
                        },
                      ),
                    ),
                  ),

                  // Play/Pause center skip buttons
                  Center(
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        if (isTv) ...[
                          IconButton(
                            icon: Icon(
                              Icons.skip_previous_rounded,
                              color: _hasPreviousEpisode() ? Colors.white : Colors.white30,
                              size: 36,
                            ),
                            onPressed: _hasPreviousEpisode()
                                ? () {
                                    _playPreviousEpisode();
                                    _startControlsAutoHide();
                                  }
                                : null,
                          ),
                          const SizedBox(width: 20),
                        ],
                        IconButton(
                          icon: Icon(
                            isPlaying ? Icons.pause_circle_filled_rounded : Icons.play_circle_filled_rounded,
                            color: Colors.white,
                            size: 56,
                          ),
                          onPressed: () {
                            if (isPlaying) {
                              _controller!.pause();
                            } else {
                              _controller!.play();
                            }
                            _startControlsAutoHide();
                          },
                        ),
                        if (isTv) ...[
                          const SizedBox(width: 20),
                          IconButton(
                            icon: Icon(
                              Icons.skip_next_rounded,
                              color: _hasNextEpisode() ? Colors.white : Colors.white30,
                              size: 36,
                            ),
                            onPressed: _hasNextEpisode()
                                ? () {
                                    _countdownTimer?.cancel();
                                    _playNextEpisode();
                                    _startControlsAutoHide();
                                  }
                                : null,
                          ),
                        ],
                      ],
                    ),
                  ),

                  // Bottom Bar
                  Positioned(
                    bottom: 0,
                    left: 0,
                    right: 0,
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                      child: Row(
                        children: [
                          Text(posStr, style: const TextStyle(color: Colors.white, fontSize: 11)),
                          Expanded(
                            child: SliderTheme(
                              data: SliderTheme.of(context).copyWith(
                                trackHeight: 3,
                                thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 6),
                                activeTrackColor: AppTheme.accentColor,
                                thumbColor: AppTheme.accentColor,
                              ),
                              child: Slider(
                                min: 0.0,
                                max: duration.inSeconds.toDouble(),
                                value: _isDragging 
                                    ? _dragValue 
                                    : position.inSeconds.toDouble().clamp(0.0, duration.inSeconds.toDouble()),
                                onChangeStart: (val) {
                                  setState(() {
                                    _isDragging = true;
                                    _dragValue = val;
                                  });
                                  _controlsTimer?.cancel();
                                },
                                onChanged: (val) {
                                  setState(() {
                                    _dragValue = val;
                                  });
                                },
                                onChangeEnd: (val) async {
                                  if (_controller != null) {
                                    await _controller!.seekTo(Duration(seconds: val.round()));
                                  }
                                  setState(() {
                                    _isDragging = false;
                                  });
                                  _startControlsAutoHide();
                                },
                              ),
                            ),
                          ),
                          Text(durStr, style: const TextStyle(color: Colors.white, fontSize: 11)),
                          const SizedBox(width: 8),
                          IconButton(
                            icon: Icon(
                              _isFullscreen ? Icons.fullscreen_exit_rounded : Icons.fullscreen_rounded,
                              color: Colors.white,
                              size: 24,
                            ),
                            onPressed: _toggleFullscreen,
                            padding: EdgeInsets.zero,
                            constraints: const BoxConstraints(),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          );
        }
      },
    );
  }

  Future<void> _loadStreamInfoBackground() async {
    final isTv = widget.subject.seasonCount > 0 || widget.subject.subjectType == 2;
    
    try {
      final resources = await ApiService.fetchPlayResources(
        widget.subject.subjectId,
        detailPath: widget.subject.detailPath,
        se: isTv ? _currentSeason : null,
        ep: isTv ? _currentEpisode : null,
      );

      if (!mounted) return;

      if (resources.isNotEmpty) {
        PlayResource bestResource = resources.reduce((a, b) => a.resolution > b.resolution ? a : b);
        final captions = await ApiService.fetchCaptions(widget.subject.subjectId, bestResource.resourceId);
        
        setState(() {
          _resources = resources;
          _selectedResource = bestResource;
          _captions = captions;
          _selectedCaption = null; // Subtitles off by default
        });
      }

      if (isTv && _seasons.isEmpty) {
        final seasons = await ApiService.fetchSeasonInfo(widget.subject.subjectId, widget.subject.detailPath);
        if (!mounted) return;
        setState(() {
          _seasons = seasons;
        });
      }
    } catch (e) {
      print('Background stream info fetch error: $e');
    }
  }
}
