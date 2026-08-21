package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.TwoFactorMethod;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lets a user choose/activate their preferred 2FA method (ticket 005's third acceptance criterion). */
@Service
public class TwoFactorPreferenceService {

    private final UserRepository userRepository;

    public TwoFactorPreferenceService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** @throws com.mcortes.authcoremc.domain.TotpNotEnrolledException if activating TOTP before enrolling a secret. */
    @Transactional
    public void activate(User user, TwoFactorMethod method) {
        user.activateTwoFactorMethod(method);
        userRepository.save(user);
    }
}
