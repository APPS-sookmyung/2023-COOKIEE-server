package com.cookiee.cookieeserver.login.jwt;

import com.cookiee.cookieeserver.login.dto.response.AccessTokenResponse;
import com.cookiee.cookieeserver.user.domain.User;
import com.cookiee.cookieeserver.user.repository.UserRepository;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.security.Key;
import java.util.Base64;
import java.util.Date;

@Getter
@Service
@RequiredArgsConstructor
public class JwtService {
    private final UserRepository userRepository;
    private final long accessTokenExpirationTime = 1000L * 60 * 60;  // 액세스 토큰 만료 기간: 1시간
    private final long refreshTokenExpirationTime = 1000L * 60 * 60 * 24 * 30;  // 리프레쉬 토큰 만료 기간: 30일

    @Value("${jwt.secret}")
    private String secretKey;

    private Key key;

    // 의존성 주입이 이루어진 후 초기화 수행 (PostConstruct) -> key 생성
    @PostConstruct
    private void _getSecretKey() {
        String keyBase64Encoded = Base64.getEncoder().encodeToString(secretKey.getBytes());
        key = Keys.hmacShaKeyFor(keyBase64Encoded.getBytes());
    }

    /**
     * 액세스 토큰 생성
     * @param userId    액세스 토큰을 부여할 유저 아이디
     * @return          액세스 토큰
     */
    public String createAccessToken(Long userId) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("role", "ROLE_USER")
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + accessTokenExpirationTime))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();  // JWT의 헤더, 클레임, 암호 등 필요한 정보를 넣고 직렬화(compact)
    }

    /**
     * 리프레쉬 토큰 생성
     * @return      생성된 리프레쉬 토큰
     */
    public String createRefreshToken() {
        Date now = new Date();
        return Jwts.builder()
                .setExpiration(new Date(now.getTime() + refreshTokenExpirationTime))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 리프레쉬 토큰 유효성 검증
     * @param accessToken   액세스 토큰
     * @param refreshToken  리프레쉬 토큰
     * @return              유저 아이디
     */
    public Long validateRefreshToken(String accessToken, String refreshToken) {
        Long userId = getUserId(accessToken);  // 액세스 토큰으로 user id 받아오기
        User user = userRepository.findByUserId(userId).orElse(null);

        if (user == null){
            throw new JwtException("해당 액세스 토큰으로 사용자를 찾을 수 없습니다.");
        }
        else{
            if (user.getRefreshToken() == null)
                throw new JwtException("리프레쉬 토큰이 존재하지 않습니다.");

            if (!user.getRefreshToken().equals(refreshToken))
                throw new JwtException("유효하지 않은 리프레쉬 토큰입니다.");
        }

        return user.getUserId();
    }

    /**
     * 토큰 정보 검증하는 메소드
     * @param token
     * @return
     */
    public boolean validate(String token) {
        try {
            Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return true;
        } catch (SecurityException | MalformedJwtException e) {
            throw new JwtException("Invalid token,"+e.getMessage());
        }catch (ExpiredJwtException e) {
            throw new JwtException("만료된 토큰입니다.");
        } catch (UnsupportedJwtException e) {
            throw new JwtException("지원되지 않는 토큰입니다.");
        } catch (IllegalArgumentException e) {
            throw new JwtException("해당 토큰으로 데이터가 존재하지 않습니다.");
        }
    }

    /**
     * 토큰을 통해 JWT 안의 claim, 즉 토큰을 복호화시켜서 정보를 꺼내는 메소드
     * @param token
     * @return
     */
    public Claims getTokenClaims(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (ExpiredJwtException e) {
            return e.getClaims();
        }
    }

    /**
     * 토큰으로 claims에 접근하여 유저 아이디 값 불러오기
     * @param accessToken   액세스 토큰
     * @return              유저 아이디
     */
    public Long getUserId(String accessToken) {
        return Long.valueOf(getTokenClaims(accessToken).getSubject());
    }

    /**
     * 액세스 토큰 갱신
     * @return
     */
    public AccessTokenResponse reissueAccessToken() throws Exception {
        // 요청에 함께 온 헤더에서 액세스 토큰 가져오기
        String accessToken = JwtHeaderUtil.getAccessToken();
        // 요청에 함께 온 헤더에서 리프레쉬 토큰 가져오기
        String refreshToken = JwtHeaderUtil.getRefreshToken();

        // 리프레쉬 토큰이 없는 경우
        if (refreshToken == null)
            throw new Exception("리프레쉬 토큰이 없습니다.");
        // 유효한 리프레쉬 토큰이 아닌 경우
        else if (!validate(refreshToken))
            throw new Exception("유효하지 않은 리프레쉬 토큰입니다.");

        // 두 토큰으로 사용자 아이디 가져오기
        Long userId = validateRefreshToken(accessToken, refreshToken);

        // 사용자 아이디로 액세스 토큰 생성
        String newAccessToken = createAccessToken(userId);

        return AccessTokenResponse.builder()
                .accessToken(newAccessToken)
                .build();
    }
}
