import React, { useState } from 'react';
import { Layout, Menu, theme, Typography, Button, Dropdown, Space, Avatar } from 'antd';
import { 
  AppstoreOutlined, 
  CloudOutlined, 
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  UserOutlined, // Thêm icon này
  DownOutlined  // Thêm icon này
} from '@ant-design/icons';
import { useNavigate, Outlet, useLocation } from 'react-router-dom';

const { Header, Sider, Content } = Layout;
const { Title } = Typography;
const MainLayout = () => {
  const [collapsed, setCollapsed] = useState(false);
// 1. Lấy thông tin user từ localStorage để kiểm tra quyền
  //const userData = JSON.parse(localStorage.getItem("user"));
  //const isAdmin = userData?.admin == true; //// Kiểm tra trường admin trong Token/User
  //const isAdmin = true;

// 1. Giả lập dữ liệu User để test (Nếu chưa có trong localStorage)
  // Cậu có thể mở F12 -> Application -> Local Storage để xem/thêm dữ liệu này
  const userData = JSON.parse(localStorage.getItem("user")) || { username: "Kiên Admin", admin: true };
  const isAdmin = userData?.admin === true;  
  const navigate = useNavigate();
  const location = useLocation();
  // thêm mới
  const userMenuItems = [
  {
    key: 'profile',
    label: 'Thông tin cá nhân',
    icon: <UserOutlined />,
    onClick: () => navigate('/profile'), // Điều hướng tới trang cá nhân
  },
  {
    type: 'divider', // Dấu gạch ngang phân cách
  },
  {
    key: 'logout',
    label: 'Đăng xuất',
    icon: <LogoutOutlined />,
    danger: true, // Chữ màu đỏ cho nổi bật
    onClick: () => {
      localStorage.removeItem("user"); // Xóa data khi đăng xuất
      navigate('/login');
    },
  },
];

  const {
    token: { colorBgContainer, borderRadiusLG },
  } = theme.useToken();

  // Cấu hình các mục trong Menu (Sidebar)
  const menuItems = [
    {
      key: '/fields',
      icon: <AppstoreOutlined />,
      label: 'Quản lý cánh đồng',
    },
    {
      key: '/weather',
      icon: <CloudOutlined />,
      label: 'Dữ liệu thời tiết',
    },
   isAdmin ? {
      key: '/users',
      icon: <AppstoreOutlined />,
      label: 'danh sách người xem',
    } : null
  
  ];

  const handleMenuClick = ({ key }) => {
    navigate(key);
  };

  const handleLogout = () => {
    // TODO: Xóa token đăng nhập ở local storage nếu có
    navigate('/login');
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider trigger={null} collapsible collapsed={collapsed} theme="dark" width={250}>
   {/*     <div style={{ height: 64, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          {/* Logo hoặc Tên ứng dụng */}
         {/* <Title level={4} style={{ color: 'white', margin: 0 }}> 
            {collapsed ? 'SF' : 'SMART FARMING'}
          </Title>
        </div> */}
        <div style={{ 
          height: 64, 
          display: 'flex', 
          alignItems: 'center', 
          justifyContent: 'center',
          padding: collapsed ? '0' : '0 16px', // Thêm padding khi mở rộng
          transition: 'all 0.2s'
        }}>
        {/* Thêm ảnh Logo */}
      <img 
          src="/src/assets/images/logo-uet.png" // Thay bằng đường dẫn file logo của cậu
          alt="logo" 
          style={{ 
          width: 32, 
          height: 32, 
          marginRight: collapsed ? 0 : 12, // Mất margin khi thu nhỏ
          transition: 'all 0.2s'
      }} 
    />
    
      {/* Hiện tên ứng dụng nếu KHÔNG bị thu nhỏ */}
      {!collapsed && (
       <Title level={4} style={{ color: 'white', margin: 0, whiteSpace: 'nowrap' }}>
        SMART FARMING
        </Title>
    )}
  </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]} // Tự động highlight menu đang đứng
          onClick={handleMenuClick}
          items={menuItems}
        />
      </Sider>
      
      <Layout>
        <Header style={{ padding: 0, background: colorBgContainer, display: 'flex', justifyContent: 'space-between', alignItems: 'center', paddingRight: 24 }}>
          <Button
            type="text"
            icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={() => setCollapsed(!collapsed)}
            style={{ fontSize: '16px', width: 64, height: 64 }}
          />
          
       {/*   <Button type="primary" danger icon={<LogoutOutlined />} onClick={handleLogout}>
            Đăng xuất
          </Button> */}
         {/* Chỗ thay đổi đây Kiên nhé */}
          <Dropdown menu={{ items: userMenuItems }} trigger={['click']}>
            <div style={{ cursor: 'pointer', display: 'flex', alignItems: 'center' }}>
             <Space>
               <Avatar icon={<UserOutlined />} style={{ backgroundColor: '#1677ff' }} />
                  <span style={{ fontWeight: '500', fontSize: '14px' }}>
                 {userData?.username || 'Người dùng'} 
                  </span>
                  <DownOutlined style={{ fontSize: '10px', color: '#8c8c8c' }} />
             </Space>
           </div>
          </Dropdown> 
        </Header>
        
        <Content
          style={{
            margin: '24px 16px',
            padding: 24,
            minHeight: 280,
            background: colorBgContainer,
            borderRadius: borderRadiusLG,
            overflow: 'auto'
          }}
        >
          {/* Điểm mấu chốt: <Outlet /> chính là cái "lỗ hổng" để nhúng các trang con (như FieldList) vào giữa Layout */}
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
};

export default MainLayout;