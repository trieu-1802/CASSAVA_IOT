// src/pages/Fields/FieldDetail/YieldTab.jsx
import React from 'react';
import { Typography, Card, Row, Col } from 'antd';

const { Title } = Typography;

const YieldTab = () => {
  return (
    <div style={{ padding: '16px 0' }}>
      <Title level={4}>Dự đoán sản lượng & Sinh trưởng</Title>
      <Row gutter={[16, 16]}>
        <Col span={12}>
          <Card title="Biểu đồ sản lượng cây sắn" bordered={false} style={{ background: '#fafafa' }}>
            <p style={{ height: '200px', textAlign: 'center', lineHeight: '200px', color: '#999' }}>
              (Khu vực hiển thị biểu đồ sản lượng)
            </p>
          </Card>
        </Col>
        <Col span={12}>
          <Card title="Biểu đồ diện tích lá" bordered={false} style={{ background: '#fafafa' }}>
            <p style={{ height: '200px', textAlign: 'center', lineHeight: '200px', color: '#999' }}>
              (Khu vực hiển thị biểu đồ diện tích lá)
            </p>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default YieldTab;