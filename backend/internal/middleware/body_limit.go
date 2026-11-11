package middleware

import (
	"net/http"

	"github.com/gogf/gf/v2/net/ghttp"

	"backend/internal/security"
)

type BodyLimitMiddleware struct {
	maxBytes int64
}

func NewBodyLimitMiddleware(maxBytes int64) *BodyLimitMiddleware {
	if maxBytes <= 0 {
		maxBytes = 25165824 // 24 MiB
	}
	return &BodyLimitMiddleware{maxBytes: maxBytes}
}

func (m *BodyLimitMiddleware) Handler(r *ghttp.Request) {
	if r.URL.Path != "/tryon" || r.Method != http.MethodPost {
		r.Middleware.Next()
		return
	}

	if r.ContentLength > m.maxBytes {
		WriteAPIError(r, security.ErrRequestTooLarge)
		return
	}

	// 限制读取流，防止 chunked 分块传输或未标明长度的超大请求
	r.Request.Body = http.MaxBytesReader(r.Response.RawWriter(), r.Request.Body, m.maxBytes)

	r.Middleware.Next()

	// 若在后续参数解析阶段触发了 MaxBytesError，拦截并转换为标准 41301 响应
	if err := r.GetError(); err != nil {
		if _, ok := err.(*http.MaxBytesError); ok || err.Error() == "http: request body too large" {
			r.SetError(nil)
			WriteAPIError(r, security.ErrRequestTooLarge)
			return
		}
	}
}
